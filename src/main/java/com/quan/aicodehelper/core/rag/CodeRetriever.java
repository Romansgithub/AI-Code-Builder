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
 * 代码检索器：从某应用的已有工程里找出与需求最相关的片段。在应用内做暴力余弦（候选数百、毫秒级，
 * 省掉向量库运维）；三类降级都不影响生成，开关关或无索引返空、向量不可用退化为关键词、片段无向量仍参与关键词打分。
 */
@Slf4j
@Service
public class CodeRetriever {

    /** 「需求点名了文件」的额外加分（加在归一化满分 1 之上）：用户点名某文件时它必须进上下文，不论语义相似度高低。 */
    private static final double FILE_MENTION_BONUS = 0.5D;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private CodeChunkMapper codeChunkMapper;

    @Resource
    private EmbeddingClient embeddingClient;

    @Resource
    private CodeIndexService codeIndexService;

    /** 检索与需求相关的片段，按相关度降序；任何异常或无结果返回空列表，永不抛出。 */
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
            // 兜底：异步预热可能未跑完或失败，同步补一次可自愈；费用只发生在确实无索引时
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
            // 横切文件（全局样式/框架入口）向量判别力低，折扣只削向量项；关键词命中与点名加成是硬信号，不受泛化影响。默认 1.0 不启用。
            double penalty = QueryTerms.genericFilePenalty(terms, chunk.getFilePath(),
                    ragProperties.getGenericFilePenalty());
            double score = vectorWeight * vectorScore * penalty + keywordWeight * keywordScore;
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

    /** 查询向量；取不到返回 null，调用方据此退化为纯关键词检索，不抛异常。 */
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
            // 单条向量损坏不应让整次检索失败，只记 0
            return 0D;
        }
        return VectorCodec.toUnitRange(VectorCodec.cosine(queryVector, chunkVector));
    }

    /** 按总数上限与单文件上限挑出最终片段；单文件上限防止被反复引用的「薄」文件挤掉其它真正要改的文件。 */
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
        // 同时按 codeGenType 过滤：类型被改过会残留旧片段，检索到等于把过时代码喂给模型
        return codeChunkMapper.selectListByQuery(QueryWrapper.create()
                .eq("appId", appId)
                .eq("codeGenType", type.getValue()));
    }
}
