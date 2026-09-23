package com.quan.aicodehelper.core.rag;

import com.mybatisflex.core.query.QueryWrapper;
import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.mapper.CodeChunkMapper;
import com.quan.aicodehelper.model.entity.CodeChunk;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码检索器：从某个应用的已有工程里，找出与本次需求最相关的代码片段。
 *
 * <p><b>为什么在应用内算而不是交给向量数据库</b>：候选集是「单个应用的全部片段」，
 * 量级在数百。把向量拉到内存里做暴力余弦，耗时在毫秒级，
 * 却省掉了一整套向量库的部署、运维与一致性维护。数据量涨到十万级以上时
 * 这个取舍才会翻转 —— 那已经是「平台有几千个应用同时被检索」的规模了。</p>
 *
 * <p><b>三种降级，都不影响生成</b>：</p>
 * <ul>
 *   <li>开关关闭 / 应用无索引 → 返回空，上下文为空，行为与改造前一致；</li>
 *   <li>查询向量算不出来（额度耗尽、服务抖动）→ <b>退化为纯关键词检索</b>。
 *       关键词那一路完全在本地计算，不依赖任何外部服务，
 *       所以「向量不可用」时仍然能捞出「需求里点名了文件名」这类高价值片段；</li>
 *   <li>片段没有向量（上次向量化失败的遗留）→ 该片段仍参与关键词打分。</li>
 * </ul>
 * <p>这三条的共同点：宁可少召回，也不要让检索失败升级成生成失败。</p>
 */
@Slf4j
@Service
public class CodeRetriever {

    /**
     * 「需求里直接点名了这个文件」的额外加分。
     *
     * <p>加在归一化得分（满分为 1）之上，因此点名文件的片段一定排在
     * 纯语义相似片段之前 —— 这是刻意的：用户说了
     * 「改一下 Login.vue 的标题」，那个文件必须进上下文，
     * 无论向量算出来的相似度是多少。</p>
     */
    private static final double FILE_MENTION_BONUS = 0.5D;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private CodeChunkMapper codeChunkMapper;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private CodeIndexService codeIndexService;

    /**
     * 检索与需求相关的代码片段。永不抛出。
     *
     * @param appId 应用 id
     * @param type  代码生成类型
     * @param query 用户本次需求
     * @return 按相关度降序的片段；任何异常或无结果时返回空列表
     */
    public List<RetrievedSlice> retrieve(long appId, CodeGenTypeEnum type, String query) {
        if (!ragProperties.isEnabled() || appId <= 0 || type == null
                || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            return doRetrieve(appId, type, query);
        } catch (Exception e) {
            log.warn("应用 {} 的代码检索失败（本轮不做检索增强）：{}", appId, e.getMessage());
            return List.of();
        }
    }

    private List<RetrievedSlice> doRetrieve(long appId, CodeGenTypeEnum type, String query) {
        List<CodeChunk> chunks = loadChunks(appId, type);
        if (chunks.isEmpty()) {
            // 兜底：首次迭代时异步预热可能还没跑完（甚至因为额度耗尽失败了）。
            // 这里同步补一次 —— 它会自愈上述两种情况，而费用只发生在
            // 「确实还没有索引」的时候，常规迭代路径上不会走到这一支。
            codeIndexService.ensureIndexed(appId, type);
            chunks = loadChunks(appId, type);
        }
        if (chunks.isEmpty()) {
            return List.of();
        }

        Set<String> terms = QueryTerms.extract(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        float[] queryVector = embedQueryQuietly(query);

        double vectorWeight = ragProperties.getVectorWeight();
        double keywordWeight = ragProperties.getKeywordWeight();

        List<RetrievedSlice> scored = new ArrayList<>(chunks.size());
        for (CodeChunk chunk : chunks) {
            if (chunk.getContent() == null || chunk.getContent().isBlank()) {
                continue;
            }
            double vectorScore = vectorSimilarity(queryVector, chunk);
            double keywordScore = QueryTerms.matchScore(terms, chunk.getSymbols(),
                    chunk.getFilePath(), chunk.getContent());
            double score = vectorWeight * vectorScore + keywordWeight * keywordScore;
            if (QueryTerms.mentionsFile(terms, chunk.getFilePath())) {
                score += FILE_MENTION_BONUS;
            }
            scored.add(new RetrievedSlice(
                    chunk.getFilePath(),
                    chunk.getStartLine() == null ? 0 : chunk.getStartLine(),
                    chunk.getEndLine() == null ? 0 : chunk.getEndLine(),
                    chunk.getContent(),
                    score, vectorScore, keywordScore));
        }
        if (scored.isEmpty()) {
            return List.of();
        }

        scored.sort(Comparator.comparingDouble(RetrievedSlice::score).reversed());
        List<RetrievedSlice> picked = applyQuota(scored);
        log.info("应用 {} 检索到 {} 条相关代码片段（候选 {} 条，向量{}）",
                appId, picked.size(), scored.size(),
                queryVector == null ? "不可用，已退化为关键词检索" : "已参与打分");
        return picked;
    }

    /**
     * 查询向量。取不到时返回 null 而不是抛异常 —— 调用方据此退化为纯关键词检索。
     */
    private float[] embedQueryQuietly(String query) {
        if (!embeddingClient.available()) {
            return null;
        }
        try {
            return embeddingClient.embedOne(query);
        } catch (Exception e) {
            log.warn("查询向量化失败，本次退化为纯关键词检索：{}", e.getMessage());
            return null;
        }
    }

    private double vectorSimilarity(float[] queryVector, CodeChunk chunk) {
        if (queryVector == null || chunk.getEmbedding() == null) {
            return 0D;
        }
        float[] chunkVector;
        try {
            chunkVector = VectorCodec.decode(chunk.getEmbedding());
        } catch (Exception e) {
            // 单条向量损坏不应让整次检索失败，只把这一个片段的向量分记 0
            return 0D;
        }
        return VectorCodec.toUnitRange(VectorCodec.cosine(queryVector, chunkVector));
    }

    /**
     * 按「总数上限」与「单文件上限」挑出最终片段。
     *
     * <p>单文件上限的作用：一个「薄」文件（比如被反复引用的工具模块）
     * 可能凭高相似度贡献很多片段，把其它真正要改的文件全挤出去。
     * 限流之后，Top-K 里必然覆盖多个文件 —— 而用户的需求几乎总是跨文件的。</p>
     */
    private List<RetrievedSlice> applyQuota(List<RetrievedSlice> sorted) {
        int topK = ragProperties.getTopK();
        int perFileLimit = Math.max(1, ragProperties.getMaxChunksPerFile());
        Map<String, Integer> takenPerFile = new HashMap<>();
        List<RetrievedSlice> picked = new ArrayList<>(topK);
        for (RetrievedSlice slice : sorted) {
            if (picked.size() >= topK) {
                break;
            }
            int taken = takenPerFile.getOrDefault(slice.filePath(), 0);
            if (taken >= perFileLimit) {
                continue;
            }
            takenPerFile.put(slice.filePath(), taken + 1);
            picked.add(slice);
        }
        return picked;
    }

    private List<CodeChunk> loadChunks(long appId, CodeGenTypeEnum type) {
        // 同时按 codeGenType 过滤：应用的生成类型若被改过，
        // 旧类型留下的片段会残留在表里，检索到它们等于把过时的代码喂给模型
        return codeChunkMapper.selectListByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .eq("codeGenType", type.getValue()));
    }
}
