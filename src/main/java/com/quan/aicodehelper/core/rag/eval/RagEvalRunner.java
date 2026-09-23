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
 * 代码检索召回评测执行器。
 *
 * <p><b>它回答什么问题</b>：RAG 的「检索」这一环到底准不准，以及为什么
 * 混合权重取 {@code 0.7 / 0.3}。前者是 Recall@K 一类的绝对值，后者只能靠消融对照 ——
 * 所以这个执行器默认把三组权重跑在同一批索引与同一批查询上，
 * 差异才只来自权重，对照才成立。</p>
 *
 * <p><b>为什么指标算在「文件级」</b>：用户说「改一下导航栏」时，他心里指向的是一个文件，
 * 不是某几行。按片段级算会把「命中了同一个文件的其它片段」判成漏召，
 * 分数低于真实体验。文件级也更方便与「需求 → 该改哪个文件」这种人工标注对齐。</p>
 *
 * <p><b>它不写任何业务数据</b>：只读索引与检索器，唯一的写操作是
 * {@link CodeIndexService#ensureIndexed} 建立/刷新 code_chunk —— 那是本来就应该有的派生数据。
 * 评测结果直接返回，不落库：这是实验数据，不是运营数据，落库要加表加 Mapper，
 * 收益不抵复杂度。</p>
 *
 * <p><b>它不该在 {@code mvn test} 里跑</b>：真实向量化需要外部网络与凭证，
 * 而测试环境的数据源是假值、{@code code-output-dir} 指向 {@code target/test-tmp}。
 * 这个执行器通过接口在真实运行环境下触发，见 {@code RagEvalController}。</p>
 */
@Slf4j
@Service
public class RagEvalRunner {

    /**
     * 一次消融跑几组。
     *
     * <p>「仅关键词」与「仅向量」是把一路权重压到 0，用来分别暴露两条路径各自的能力边界；
     * 「混合」用配置里的真实权重，是线上实际生效的那一组。</p>
     */
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

    /**
     * 跑一轮检索召回评测。
     *
     * @param ablation 是否跑三组权重消融。为 false 时只跑「混合」一组 ——
     *                 检索每条用例都要一次 embedding 调用，三组就是三倍。
     *                 只是想确认「有没有变差」时没必要付这个成本。
     */
    public RagEvalResult run(boolean ablation) {
        RagEvalSet set = ragEvalSetLoader.load();
        long startedAt = System.currentTimeMillis();

        RagEvalResult result = new RagEvalResult();
        result.setSetName(set.getName());
        result.setSetVersion(set.getVersion());
        result.setTotalCases(set.getCases().size());
        result.setTopK(ragProperties.getTopK());
        result.setMaxChunksPerFile(ragProperties.getMaxChunksPerFile());
        // 先记下来：向量不可用时所有指标都会退化成纯关键词，
        // 而「纯关键词」恰好也是消融里的一组，两者混在一起会被误读成「向量没有价值」
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
                // 直接改可变配置对象而不是重启：三组的工程、索引、查询完全相同，
                // 唯一变量就是这里的两行权重 —— 消融实验的可信度来自这一点
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
            // 评测改的是全局配置，抛异常也必须还原 —— 否则线上会一直用
            // 「仅关键词」或「仅向量」的权重跑，而且不会有任何提示
            ragProperties.setVectorWeight(originVector);
            ragProperties.setKeywordWeight(originKeyword);
        }

        result.setTotalCostMs(System.currentTimeMillis() - startedAt);
        return result;
    }

    /**
     * 建立/刷新各被测应用的索引，并记录片段数。
     *
     * <p>先把索引做完再逐组检索：三组共用同一份索引，避免某一组恰好在索引重建的
     * 间隙里跑，导致组间不可比。</p>
     */
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

    /**
     * 跑单条用例。检索器自身永不抛出（失败返回空列表），
     * 所以这里不需要 try-catch —— 空结果本身就是「这一条漏召了」这个事实。
     *
     * <p>包可见而非 private：指标算法是这套评测的核心，必须能被单独测试。
     * 只靠「跑一次真实评测看数字」是测不出边界的 —— 真实数据里
     * 很难同时出现「期望文件有 3 个」「同一文件多个片段」「结果为空」这些情形。</p>
     */
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

        // 按排名去重但保持顺序：指标是文件级的，同一文件的多个片段只应算一次，
        // 而「第一次出现在第几名」这个顺序恰恰是 MRR 要用的信息
        Set<String> ordered = new LinkedHashSet<>();
        for (RetrievedSlice slice : slices) {
            ordered.add(slice.filePath());
        }
        List<String> retrievedFiles = new ArrayList<>(ordered);
        outcome.setRetrievedFiles(retrievedFiles);

        // 检索结果已按总分降序，取第一条用来判断「向量到底有没有参与排序」
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
     * 汇总一组用例的指标。各项都是「按用例求平均」而不是「按片段加权」——
     * 每条需求的重要性是相同的，让片段多的用例拉高整体分数会掩盖跨文件需求的漏召。
     *
     * <p>包可见以便单测：空集合必须返回全 0 而不是抛异常（消融时若某个类型
     * 一条用例都没有，整轮评测不该因此崩掉）。</p>
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

    /**
     * 一种权重配置。
     *
     * @param name         报告里的显示名
     * @param vectorWeight 向量余弦权重
     * @param keywordWeight 关键词命中率权重
     */
    private record Mode(String name, double vectorWeight, double keywordWeight) {
    }
}
