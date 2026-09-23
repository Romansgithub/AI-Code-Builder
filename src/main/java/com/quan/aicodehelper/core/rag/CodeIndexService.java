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
 * 工程代码索引服务：把磁盘工程代码变成可检索的向量片段。两条触发路径（生成后异步预热 + 检索前兜底）；
 * 按文件摘要做增量比对；索引是派生数据，失败则降级、永不抛出。
 */
@Slf4j
@Service
public class CodeIndexService {

    /** 正在索引中的应用，避免异步预热与检索兜底同时索引同一应用（重复向量化、互相踩踏）。 */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    @Resource
    private RagProperties ragProperties;

    @Resource
    private CodeChunkMapper codeChunkMapper;

    @Resource
    private EmbeddingClient embeddingClient;

    /** 连续向量化失败达到此值即放弃本轮：连续失败通常是服务不可用或凭证失效，不必逐个重试放大开销。 */
    private static final int MAX_CONSECUTIVE_EMBED_FAILURES = 2;

    /**
     * 工程产物目录：{@code {code-output-root}/{codeGenType}_{appId}}；每次重新取根目录而非缓存，因为评测跑批期间产物根会被临时切换。
     */
    public static Path projectDir(CodeGenTypeEnum type, long appId) {
        return Paths.get(AppConstant.getCodeOutputRootDir(), type.getValue() + "_" + appId);
    }

    public void indexAfterGeneration(long appId, CodeGenTypeEnum type) {
        if (!ragProperties.isEnabled() || !ragProperties.isIndexOnComplete()) {
            return;
        }
        // 评测用负数 appId 且产物写到独立目录，索引无意义且白烧额度，直接跳过
        if (appId <= 0 || type == null || EvalScope.isActive()) {
            return;
        }
        Thread.ofVirtual().name("rag-index-" + appId).start(() -> {
            try {
                ensureIndexed(appId, type);
            } catch (Exception e) {
                // 虚拟线程内异常无人接收会被静默吞掉，必须在此兜住
                log.warn("应用 {} 的异步索引任务异常：{}", appId, e.getMessage());
            }
        });
    }

    /** 建立或刷新索引（同步），永不抛出。 */
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

            // 先删后插：同一 (appId, filePath, chunkIndex) 有唯一索引，反过来会撞键；中断只留「该文件暂时搜不到」，派生数据允许。
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

    /** 清理磁盘上已删除文件的片段。@return 清理掉的文件数 */
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
     * 判断某文件是否需重新切块/向量化：内容变了、embedding 模型换了（不同模型向量不共线，混算余弦会无声出错），或存在无向量的片段（上次失败遗留，不补则永久只能关键词召回）。
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
                    // 向量缺失时连模型名一起留空，否则增量比对会误认「向量是最新模型产出」而永不补它
                    .embeddingModel(vector == null ? null : currentModel)
                    .createTime(now)
                    .updateTime(now)
                    .build());
        }
        return rows;
    }
}
