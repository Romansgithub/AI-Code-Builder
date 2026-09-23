package com.quan.aicodehelper.core.rag.eval;

import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.core.rag.CodeIndexService;
import com.quan.aicodehelper.core.rag.CodeRetriever;
import com.quan.aicodehelper.core.rag.EmbeddingClient;
import com.quan.aicodehelper.core.rag.RetrievedSlice;
import com.quan.aicodehelper.model.dto.rag.RagEvalApp;
import com.quan.aicodehelper.model.dto.rag.RagEvalCase;
import com.quan.aicodehelper.model.dto.rag.RagEvalSet;
import com.quan.aicodehelper.model.enums.RetrievalKind;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代码检索召回评测执行器：三组权重（仅关键词/仅向量/混合）同批跑，差异只来自权重，消融才成立；指标算文件级，不在 mvn test 里跑。
 */
@Slf4j
@Service
public class RagEvalRunner {

    /** 消融分组：「仅关键词」「仅向量」压一路权重为 0 以暴露各路能力边界；「混合」用线上真实权重。 */
    private static final Mode KEYWORD_ONLY = new Mode("仅关键词(0/1)", 0.0D, 1.0D);
    private static final Mode VECTOR_ONLY = new Mode("仅向量(1/0)", 1.0D, 0.0D);

    @Resource
    private RagEvalSetLoader ragEvalSetLoader;

    @Resource
    private CodeIndexService codeIndexService;

    @Resource
    private CodeRetriever codeRetriever;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private EmbeddingClient embeddingClient;

    public RagEvalResult run() {
        return run(true);
    }

    /** @param ablation 是否跑三组消融；false 只跑混合组，省去三倍 embedding 调用 */
    public RagEvalResult run(boolean ablation) {
        RagEvalSet set = ragEvalSetLoader.load();
        long startedAt = System.currentTimeMillis();

        RagEvalResult result = new RagEvalResult();
        result.setSetName(set.getName());
        result.setSetVersion(set.getVersion());
        result.setTotalCases(set.getCases().size());
        result.setTopK(ragProperties.getTopK());
        result.setMaxChunksPerFile(ragProperties.getMaxChunksPerFile());
        // 折扣系数进报告头：配置决定这批数字能否与上一批直接比，配置与数字须写在同一份报告
        result.setGenericFilePenalty(ragProperties.getGenericFilePenalty());
        // 先记 embedding 是否可用：不可用时指标退化为纯关键词，与消融「纯关键词」组混读会误判向量无价值
        result.setEmbeddingAvailable(embeddingClient.available());

        Map<String, RagEvalApp> appByKey = new HashMap<>();
        for (RagEvalApp app : set.getApps()) {
            appByKey.put(app.getKey(), app);
        }

        indexApps(set, result);

        double originVector = ragProperties.getVectorWeight();
        double originKeyword = ragProperties.getKeywordWeight();
        List<Mode> modes = ablation
                ? List.of(KEYWORD_ONLY, VECTOR_ONLY, new Mode("混合", originVector, originKeyword))
                : List.of(new Mode("混合", originVector, originKeyword));

        try {
            for (Mode mode : modes) {
                // 直接改可变配置对象而不是重启：三组的工程、索引、查询完全相同，唯一变量就是这两行权重
                ragProperties.setVectorWeight(mode.vectorWeight());
                ragProperties.setKeywordWeight(mode.keywordWeight());
                RagEvalResult.ModeResult modeResult = runMode(set, appByKey, mode);
                result.getModes().add(modeResult);
                RagEvalResult.MetricSummary s = modeResult.getOverall();
                log.info("检索评测[{}]：Recall@{}={}，MRR={}，命中率={}",
                        mode.name(), result.getTopK(),
                        String.format("%.1f%%", s.getRecallAtK() * 100),
                        String.format("%.3f", s.getMrr()),
                        String.format("%.1f%%", s.getHitRate() * 100));
            }
        } finally {
            // 评测改的是全局配置，抛异常也必须还原，否则线上会一直用消融权重跑且无提示
            ragProperties.setVectorWeight(originVector);
            ragProperties.setKeywordWeight(originKeyword);
        }

        result.setTotalCostMs(System.currentTimeMillis() - startedAt);
        return result;
    }

    /** 建立/刷新各应用索引并记录片段数。先建完索引再逐组检索，三组共用同一份索引以保证可比。 */
    private void indexApps(RagEvalSet set, RagEvalResult result) {
        long startedAt = System.currentTimeMillis();
        for (RagEvalApp app : set.getApps()) {
            codeIndexService.ensureIndexed(app.getAppId(), app.getCodeGenType());
            RagEvalResult.AppIndexStat stat = new RagEvalResult.AppIndexStat();
            stat.setKey(app.getKey());
            stat.setLabel(app.getLabel());
            stat.setAppId(app.getAppId());
            stat.setChunks(codeIndexService.countChunks(app.getAppId()));
            result.getApps().add(stat);
            log.info("检索评测：应用 {}({}) 索引 {} 个片段",
                    app.getLabel(), app.getAppId(), stat.getChunks());
        }
        result.setIndexCostMs(System.currentTimeMillis() - startedAt);
    }

    private RagEvalResult.ModeResult runMode(RagEvalSet set,
                                             Map<String, RagEvalApp> appByKey,
                                             Mode mode) {
        RagEvalResult.ModeResult modeResult = new RagEvalResult.ModeResult();
        modeResult.setModeName(mode.name());
        modeResult.setVectorWeight(mode.vectorWeight());
        modeResult.setKeywordWeight(mode.keywordWeight());

        List<RagEvalResult.CaseOutcome> outcomes = new ArrayList<>(set.getCases().size());
        int vectorSignal = 0;
        int keywordSilent = 0;
        for (RagEvalCase c : set.getCases()) {
            RagEvalApp app = appByKey.get(c.getAppKey());
            if (app == null) {
                continue;
            }
            RagEvalResult.CaseOutcome outcome = evaluateOne(c, app);
            outcomes.add(outcome);
            if (outcome.getTopVectorScore() > 0D) {
                vectorSignal++;
            }
            if (outcome.getTopKeywordScore() <= 0D) {
                keywordSilent++;
            }
        }

        modeResult.setCases(outcomes);
        modeResult.setVectorSignalCases(vectorSignal);
        modeResult.setKeywordSilentCases(keywordSilent);
        modeResult.setOverall(summarize(outcomes));
        modeResult.setPointed(summarize(byKind(outcomes, RetrievalKind.POINTED)));
        modeResult.setSemantic(summarize(byKind(outcomes, RetrievalKind.SEMANTIC)));
        return modeResult;
    }

    /** 跑单条用例。检索器永不抛出，空结果即「漏召」，故无需 try-catch；包可见以便单测覆盖指标边界。 */
    RagEvalResult.CaseOutcome evaluateOne(RagEvalCase c, RagEvalApp app) {
        RagEvalResult.CaseOutcome outcome = new RagEvalResult.CaseOutcome();
        outcome.setCaseId(c.getId());
        outcome.setAppKey(c.getAppKey());
        outcome.setKind(c.getKind());
        outcome.setQuery(c.getQuery());
        outcome.setExpectedFiles(c.getExpectedFiles());

        long startedAt = System.currentTimeMillis();
        List<RetrievedSlice> slices =
                codeRetriever.retrieve(app.getAppId(), app.getCodeGenType(), c.getQuery());
        outcome.setCostMs(System.currentTimeMillis() - startedAt);
        outcome.setRetrievedCount(slices.size());

        // 按排名去重且保序：文件级指标下同一文件多片段只算一次，而首次出现名次正是 MRR 所需
        Set<String> ordered = new LinkedHashSet<>();
        for (RetrievedSlice slice : slices) {
            ordered.add(slice.filePath());
        }
        List<String> retrievedFiles = new ArrayList<>(ordered);
        outcome.setRetrievedFiles(retrievedFiles);

        // 取首条判断「向量是否参与了排序」
        if (!slices.isEmpty()) {
            outcome.setTopVectorScore(slices.get(0).vectorScore());
            outcome.setTopKeywordScore(slices.get(0).keywordScore());
        }

        Set<String> expected = new HashSet<>(c.getExpectedFiles());
        int hit = 0;
        int firstHitRank = 0;
        for (int i = 0; i < retrievedFiles.size(); i++) {
            if (expected.contains(retrievedFiles.get(i))) {
                hit++;
                if (firstHitRank == 0) {
                    firstHitRank = i + 1;
                }
            }
        }
        outcome.setFirstHitRank(firstHitRank);
        outcome.setRecall(expected.isEmpty() ? 0D : (double) hit / expected.size());
        outcome.setPrecision(retrievedFiles.isEmpty() ? 0D : (double) hit / retrievedFiles.size());
        outcome.setReciprocalRank(firstHitRank > 0 ? 1.0D / firstHitRank : 0D);
        return outcome;
    }

    private List<RagEvalResult.CaseOutcome> byKind(List<RagEvalResult.CaseOutcome> outcomes,
                                                   RetrievalKind kind) {
        List<RagEvalResult.CaseOutcome> filtered = new ArrayList<>();
        for (RagEvalResult.CaseOutcome o : outcomes) {
            if (o.getKind() == kind) {
                filtered.add(o);
            }
        }
        return filtered;
    }

    /**
     * 汇总一组用例指标，按用例求平均而非按片段加权，避免片段多的用例掩盖跨文件漏召；空集合返回全 0 而非抛异常。
     */
    RagEvalResult.MetricSummary summarize(List<RagEvalResult.CaseOutcome> outcomes) {
        RagEvalResult.MetricSummary summary = new RagEvalResult.MetricSummary();
        int size = outcomes.size();
        summary.setCases(size);
        if (size == 0) {
            return summary;
        }
        int hits = 0;
        int topOne = 0;
        double recall = 0D;
        double precision = 0D;
        double mrr = 0D;
        for (RagEvalResult.CaseOutcome o : outcomes) {
            if (o.getFirstHitRank() > 0) {
                hits++;
            }
            if (o.getFirstHitRank() == 1) {
                topOne++;
            }
            recall += o.getRecall();
            precision += o.getPrecision();
            mrr += o.getReciprocalRank();
        }
        summary.setHits(hits);
        summary.setTopOneHits(topOne);
        summary.setHitRate((double) hits / size);
        summary.setRecallAtK(recall / size);
        summary.setPrecisionAtK(precision / size);
        summary.setMrr(mrr / size);
        return summary;
    }

    private record Mode(String name, double vectorWeight, double keywordWeight) {
    }
}
