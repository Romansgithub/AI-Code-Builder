package com.quan.aicodehelper.core.rag;

import com.mybatisflex.core.query.QueryWrapper;
import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.core.eval.EvalScope;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.mapper.CodeChunkMapper;
import com.quan.aicodehelper.model.entity.CodeChunk;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工程代码索引服务：把磁盘上的工程代码变成可检索的向量片段。
 *
 * <p><b>两条触发路径</b>：</p>
 * <ol>
 *   <li>{@link #indexAfterGeneration} —— 生成完成后的<b>异步预热</b>。
 *       把「第一次迭代要现场向量化整个工程」的等待，挪到用户还在看生成结果的那几秒里；</li>
 *   <li>{@link #ensureIndexed} —— 检索前的<b>兜底</b>，只在「这个应用一条索引都没有」时
 *       才会被真正调用（见 {@link #hasIndex}）。这样常规迭代路径上零额外开销，
 *       而预热失败（额度耗尽、服务抖动）也不会让检索永久失效 ——
 *       下一次迭代会重新尝试，属于自愈。</li>
 * </ol>
 *
 * <p><b>增量而不是全量</b>：以「文件摘要」为单位比对。按文件比而不是按片段比，
 * 是因为一个文件的改动会连带移动后面所有片段的行号 —— 逐片段比对会把
 * 整个文件判成「全变了」，增量就退化成全量。</p>
 *
 * <p><b>永不抛出</b>：索引是检索增强的派生数据，它失败的正确后果是
 * 「这一轮没有检索增强」，而不是「用户拿不到代码」。所以对外方法全部
 * 自行兜底，与评测埋点同样的原则。</p>
 */
@Slf4j
@Service
public class CodeIndexService {

    /**
     * 正在索引中的应用。用来防止「生成完成触发的异步索引」与
     * 「检索前的兜底索引」同时跑同一个应用 —— 两者会重复切块、
     * 重复向量化（等于重复花钱），还会在删除/插入之间互相踩踏。
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Resource
    private RagProperties ragProperties;

    @Resource
    private CodeChunkMapper codeChunkMapper;

    @Resource
    private EmbeddingClient embeddingClient;

    /**
     * 连续向量化失败多少次就放弃本轮。
     *
     * <p>第一次失败可能是偶发，继续下一个文件；但连续失败通常意味着
     * 服务不可用或凭证失效 —— 此时对剩余每个文件都重试一遍，
     * 只会把「一次失败」放大成「几十次失败 + 一分钟等待」。</p>
     */
    private static final int MAX_CONSECUTIVE_EMBED_FAILURES = 2;

    /**
     * 工程产物目录：{@code {code-output-root}/{codeGenType}_{appId}}。
     *
     * <p>每次调用都重新取根目录，不能缓存成常量 —— 评测跑批期间
     * 产物根会被临时切换（见 {@code EvalRunner}），固化的路径会指错地方。</p>
     */
    public static Path projectDir(CodeGenTypeEnum type, long appId) {
        return Paths.get(AppConstant.getCodeOutputRootDir(), type.getValue() + "_" + appId);
    }

    /**
     * 生成完成后的异步预热索引。
     *
     * @param appId 应用 id
     * @param type  代码生成类型
     */
    public void indexAfterGeneration(long appId, CodeGenTypeEnum type) {
        if (!ragProperties.isEnabled() || !ragProperties.isIndexOnComplete()) {
            return;
        }
        // 评测跑批用的是负数 appId 且产物写到独立目录，索引它毫无意义
        // 还会白烧向量化额度，故直接跳过
        if (appId <= 0 || type == null || EvalScope.isActive()) {
            return;
        }
        Thread.ofVirtual().name("rag-index-" + appId).start(() -> {
            try {
                ensureIndexed(appId, type);
            } catch (Exception e) {
                // 虚拟线程内抛出的异常无人接收，会被静默吞掉 —— 必须在这里兜住
                log.warn("应用 {} 的异步索引任务异常：{}", appId, e.getMessage());
            }
        });
    }

    /**
     * 建立或刷新索引（同步）。永不抛出。
     */
    public void ensureIndexed(long appId, CodeGenTypeEnum type) {
        if (!ragProperties.isEnabled() || appId <= 0 || type == null) {
            return;
        }
        if (!inFlight.add(appId)) {
            log.debug("应用 {} 已有索引任务在进行中，跳过本次触发", appId);
            return;
        }
        try {
            doIndex(appId, type);
        } catch (Exception e) {
            log.warn("应用 {} 的代码索引失败（检索将降级为无增强，不影响生成）：{}", appId, e.getMessage());
        } finally {
            inFlight.remove(appId);
        }
    }

    /**
     * 该应用的片段总数，供观测与排查使用。
     */
    public long countChunks(long appId) {
        try {
            return codeChunkMapper.selectCountByQuery(QueryWrapper.create().eq("appId", appId));
        } catch (Exception e) {
            return 0L;
        }
    }

    private void doIndex(long appId, CodeGenTypeEnum type) {
        Path dir = projectDir(type, appId);
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<CodeFileScanner.SourceFile> files =
                CodeFileScanner.scan(dir, ragProperties.getMaxFileBytes());
        if (files.isEmpty()) {
            return;
        }

        List<CodeChunk> existing = codeChunkMapper.selectListByQuery(
                QueryWrapper.create().eq("appId", appId));
        Map<String, List<CodeChunk>> existingByFile = new HashMap<>();
        for (CodeChunk chunk : existing) {
            existingByFile.computeIfAbsent(chunk.getFilePath(), k -> new ArrayList<>()).add(chunk);
        }

        Set<String> currentPaths = files.stream()
                .map(CodeFileScanner.SourceFile::relativePath)
                .collect(Collectors.toCollection(HashSet::new));

        int removedFiles = purgeDeletedFiles(appId, existingByFile.keySet(), currentPaths);

        String currentModel = embeddingClient.modelName();
        List<CodeFileScanner.SourceFile> staleFiles = new ArrayList<>();
        for (CodeFileScanner.SourceFile file : files) {
            if (needsIndex(existingByFile.get(file.relativePath()), file, currentModel)) {
                staleFiles.add(file);
            }
        }
        if (staleFiles.isEmpty()) {
            if (removedFiles > 0) {
                log.info("应用 {} 索引已同步：清理 {} 个已删除文件的片段，无文件需要重新切块",
                        appId, removedFiles);
            }
            return;
        }

        long startedAt = System.currentTimeMillis();
        int indexedFiles = 0;
        int insertedChunks = 0;
        int embeddedChunks = 0;
        int consecutiveFailures = 0;

        for (CodeFileScanner.SourceFile file : staleFiles) {
            List<CodeSlice> slices = CodeChunker.chunk(file.relativePath(), file.content(),
                    file.fileHash(), ragProperties.getChunkMaxLines(),
                    ragProperties.getChunkOverlapLines());
            if (slices.isEmpty()) {
                continue;
            }

            List<float[]> vectors = null;
            try {
                vectors = embeddingClient.embedAll(
                        slices.stream().map(CodeSlice::toEmbeddingText).toList());
                consecutiveFailures = 0;
                embeddedChunks += vectors.size();
            } catch (BusinessException e) {
                // 额度耗尽属于「继续做也不会成功」，必须中止整轮而不是逐文件重试
                log.warn("RAG 索引因预算耗尽中止（已处理 {} 个文件）：{}", indexedFiles, e.getMessage());
                return;
            } catch (Exception e) {
                consecutiveFailures++;
                log.warn("文件 {} 向量化失败，本次仅保留关键词可检索：{}",
                        file.relativePath(), e.getMessage());
                if (consecutiveFailures >= MAX_CONSECUTIVE_EMBED_FAILURES) {
                    log.warn("连续 {} 次向量化失败，中止本轮索引（避免逐文件重复失败）",
                            consecutiveFailures);
                    return;
                }
            }

            // 先删后插：同一 (appId, filePath, chunkIndex) 上有唯一索引，
            // 反过来做会撞唯一键。删插之间若中断，留下的只是「该文件暂时搜不到」，
            // 下一次索引会重新补上 —— 派生数据允许这种中间态。
            deleteFileChunks(appId, file.relativePath());
            List<CodeChunk> rows = buildRows(appId, type, slices, vectors, currentModel);
            for (CodeChunk row : rows) {
                codeChunkMapper.insert(row);
            }
            insertedChunks += rows.size();
            indexedFiles++;
        }

        log.info("应用 {} 索引完成：{} 个文件重新切块，写入 {} 个片段"
                        + "（其中 {} 个已向量化），清理 {} 个已删除文件，耗时 {} ms",
                appId, indexedFiles, insertedChunks, embeddedChunks, removedFiles,
                System.currentTimeMillis() - startedAt);
    }

    /**
     * 清理磁盘上已不存在（或被删除）的文件的片段。
     *
     * @return 清理掉的文件数
     */
    private int purgeDeletedFiles(long appId, Set<String> indexedPaths, Set<String> currentPaths) {
        int removed = 0;
        for (String path : indexedPaths) {
            if (!currentPaths.contains(path)) {
                deleteFileChunks(appId, path);
                removed++;
            }
        }
        return removed;
    }

    /**
     * 判断某个文件是否需要（重新）切块与向量化。
     *
     * <p>三种情形都要重算：</p>
     * <ol>
     *   <li><b>文件内容变了</b>（摘要不同）—— 常规情况；</li>
     *   <li><b>embedding 模型换了</b> —— 不同模型的向量不在同一语义空间，
     *       混着算余弦得出的相似度毫无意义，而且不会报错，只会静默给出错误排序；</li>
     *   <li><b>存在没有向量的片段</b> —— 上一次向量化失败（额度耗尽、服务抖动）。
     *       不补这一步，失败的片段会被「摘要相同」永久判定为已索引，
     *       从此只能被关键词检索到，而且没人会注意到。</li>
     * </ol>
     */
    private boolean needsIndex(List<CodeChunk> existing, CodeFileScanner.SourceFile file,
                              String currentModel) {
        if (existing == null || existing.isEmpty()) {
            return true;
        }
        CodeChunk any = existing.get(0);
        if (!Objects.equals(any.getFileHash(), file.fileHash())) {
            return true;
        }
        if (!Objects.equals(any.getEmbeddingModel(), currentModel)) {
            return true;
        }
        for (CodeChunk chunk : existing) {
            if (chunk.getEmbedding() == null) {
                return true;
            }
        }
        return false;
    }

    private void deleteFileChunks(long appId, String filePath) {
        codeChunkMapper.deleteByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .eq("filePath", filePath));
    }

    private List<CodeChunk> buildRows(long appId, CodeGenTypeEnum type, List<CodeSlice> slices,
                                      List<float[]> vectors, String currentModel) {
        LocalDateTime now = LocalDateTime.now();
        List<CodeChunk> rows = new ArrayList<>(slices.size());
        for (int i = 0; i < slices.size(); i++) {
            CodeSlice slice = slices.get(i);
            float[] vector = (vectors != null && i < vectors.size()) ? vectors.get(i) : null;
            rows.add(CodeChunk.builder()
                    .appId(appId)
                    .codeGenType(type.getValue())
                    .filePath(slice.filePath())
                    .chunkIndex(slice.chunkIndex())
                    .startLine(slice.startLine())
                    .endLine(slice.endLine())
                    .content(slice.content())
                    .contentHash(slice.contentHash())
                    .fileHash(slice.fileHash())
                    .symbols(slice.symbols())
                    .embedding(VectorCodec.encode(vector))
                    .dim(vector == null ? null : vector.length)
                    // 向量缺失时刻意连模型名一起留空：否则下次增量比对会误认为
                    // 「这个片段的向量是最新模型产出的」，永远不去补它
                    .embeddingModel(vector == null ? null : currentModel)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        }
        return rows;
    }
}
