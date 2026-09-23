package com.quan.aicodehelper.core.rag.eval;

import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.core.rag.CodeIndexService;
import com.quan.aicodehelper.core.rag.CodeRetriever;
import com.quan.aicodehelper.core.rag.EmbeddingClient;
import com.quan.aicodehelper.core.rag.RetrievedSlice;
import com.quan.aicodehelper.model.dto.rag.RagEvalApp;
import com.quan.aicodehelper.model.dto.rag.RagEvalCase;
import com.quan.aicodehelper.model.dto.rag.RagEvalSet;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.model.enums.RetrievalKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索召回指标计算。
 *
 * <p>这些断言守的是「指标本身没错」—— 与检索算法无关。之所以值得单独测，
 * 是因为指标算错时的表现形式与「检索效果差」一模一样（都是低分），
 * 而人会先去怀疑检索算法、去调参数，很难想到是分母算错了。</p>
 */
@DisplayName("检索召回评测执行器")
class RagEvalRunnerTest {

    private static final long APP_ID = 457738954912059392L;
    private static final String NAVBAR = "src/components/Navbar.vue";
    private static final String HOME = "src/pages/Home.vue";
    private static final String GUIDE = "src/pages/Guide.vue";
    private static final String MAIN_CSS = "src/styles/main.css";
    private static final String ABOUT = "src/pages/About.vue";

    private CodeRetriever codeRetriever;
    private RagEvalRunner runner;

    @BeforeEach
    void setUp() {
        codeRetriever = mock(CodeRetriever.class);
        runner = new RagEvalRunner();
        ReflectionTestUtils.setField(runner, "codeRetriever", codeRetriever);
    }

    @Nested
    @DisplayName("单条用例打分")
    class EvaluateOne {

        @Test
        @DisplayName("期望文件排在首位时，召回、精确率与 MRR 都应满分")
        void topRankHitIsFullScore() {
            stubRetrieval(slice(NAVBAR, 1.2D));

            RagEvalResult.CaseOutcome outcome =
                    runner.evaluateOne(evalCase(NAVBAR), evalApp());

            assertThat(outcome.getRecall()).isEqualTo(1.0D);
            assertThat(outcome.getPrecision()).isEqualTo(1.0D);
            assertThat(outcome.getReciprocalRank()).isEqualTo(1.0D);
            assertThat(outcome.getFirstHitRank()).isEqualTo(1);
        }

        @Test
        @DisplayName("期望文件未出现在结果里时各项为 0 而非 null —— 「没命中」与「没测」是两回事")
        void missYieldsZeroNotUnknown() {
            stubRetrieval(slice(HOME, 0.9D), slice(GUIDE, 0.8D));

            RagEvalResult.CaseOutcome outcome =
                    runner.evaluateOne(evalCase(NAVBAR), evalApp());

            assertThat(outcome.getRecall()).isZero();
            assertThat(outcome.getPrecision()).isZero();
            assertThat(outcome.getReciprocalRank()).isZero();
            assertThat(outcome.getFirstHitRank())
                    .as("0 表示全部落空，用它可以一眼从报告里筛出漏召的用例")
                    .isZero();
        }

        @Test
        @DisplayName("同一文件返回多个片段只算一个文件 —— 文件级口径才不会把命中的用例判成低分")
        void duplicateFileCountsOnce() {
            stubRetrieval(slice(NAVBAR, 1.2D), slice(NAVBAR, 1.1D), slice(NAVBAR, 1.0D));

            RagEvalResult.CaseOutcome outcome =
                    runner.evaluateOne(evalCase(NAVBAR), evalApp());

            assertThat(outcome.getRetrievedFiles())
                    .as("去重后只应剩一个文件，否则精确率会被片段数稀释")
                    .containsExactly(NAVBAR);
            assertThat(outcome.getPrecision()).isEqualTo(1.0D);
            assertThat(outcome.getRetrievedCount())
                    .as("原始片段数仍要保留，它是判断「单文件上限是否生效」的依据")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("期望多个文件时按命中比例计算召回，而不是有命中就算满分")
        void multiExpectedFilesUseRatio() {
            stubRetrieval(slice(NAVBAR, 1.2D), slice(HOME, 0.9D));

            RagEvalResult.CaseOutcome outcome =
                    runner.evaluateOne(evalCase(NAVBAR, GUIDE), evalApp());

            assertThat(outcome.getRecall())
                    .as("跨文件需求只召回一半，就应如实体现为 0.5")
                    .isEqualTo(0.5D);
            assertThat(outcome.getPrecision()).isEqualTo(0.5D);
        }

        @Test
        @DisplayName("检索返回空时不抛异常，只体现为漏召 —— 检索器永不抛出，评测也不该因此崩")
        void emptyRetrievalIsJustAMiss() {
            stubRetrieval();

            RagEvalResult.CaseOutcome outcome =
                    runner.evaluateOne(evalCase(NAVBAR), evalApp());

            assertThat(outcome.getRecall()).isZero();
            assertThat(outcome.getPrecision()).isZero();
            assertThat(outcome.getRetrievedFiles()).isEmpty();
            assertThat(outcome.getTopVectorScore()).isZero();
        }

        @Test
        @DisplayName("命中排在第三位时 MRR 取 1/3 —— 排名必须进指标，否则「靠后命中」与「首位命中」无法区分")
        void rankFeedsMrr() {
            stubRetrieval(slice(HOME, 1.0D), slice(GUIDE, 0.9D), slice(NAVBAR, 0.8D));

            RagEvalResult.CaseOutcome outcome =
                    runner.evaluateOne(evalCase(NAVBAR), evalApp());

            assertThat(outcome.getFirstHitRank()).isEqualTo(3);
            assertThat(outcome.getReciprocalRank()).isCloseTo(1.0D / 3, within(1e-9));
            assertThat(outcome.getRecall())
                    .as("召回只看有没有，与排名无关；排名由 MRR 单独承担")
                    .isEqualTo(1.0D);
        }

        @Test
        @DisplayName("top-1 的分数构成要带出来 —— 漏召时靠它判断是向量没召回还是关键词没召回")
        void topScoresAreKeptForDiagnosis() {
            stubRetrieval(new RetrievedSlice(NAVBAR, 1, 20, "//c", 0.91D, 0.83D, 0.25D));

            RagEvalResult.CaseOutcome outcome =
                    runner.evaluateOne(evalCase(NAVBAR), evalApp());

            assertThat(outcome.getTopVectorScore()).isEqualTo(0.83D);
            assertThat(outcome.getTopKeywordScore()).isEqualTo(0.25D);
        }
    }

    @Nested
    @DisplayName("按类型分组汇总")
    class Summarize {

        @Test
        @DisplayName("空分组返回全 0 而不抛异常 —— 消融时某一类用例为零，不该让整轮评测崩掉")
        void emptyGroupIsAllZero() {
            RagEvalResult.MetricSummary summary = runner.summarize(List.of());

            assertThat(summary.getCases()).isZero();
            assertThat(summary.getRecallAtK()).isZero();
            assertThat(summary.getMrr()).isZero();
            assertThat(summary.getHitRate()).isZero();
            assertThat(summary.getTopOneHits()).isZero();
        }

        @Test
        @DisplayName("汇总按用例平均，命中率只数「至少命中一个期望文件」的用例")
        void averagesPerCaseAndCountsHits() {
            RagEvalResult.MetricSummary summary =
                    runner.summarize(List.of(outcome(1, 1.0D), outcome(0, 0.0D)));

            assertThat(summary.getCases()).isEqualTo(2);
            assertThat(summary.getHits()).isEqualTo(1);
            assertThat(summary.getHitRate())
                    .as("命中率与召回率不是一回事：前者数用例，后者按期望文件的比例")
                    .isEqualTo(0.5D);
            assertThat(summary.getRecallAtK()).isEqualTo(0.5D);
            assertThat(summary.getMrr()).isEqualTo(0.5D);
        }

        @Test
        @DisplayName("Top-1 只数排在首位的用例 —— MRR 把「第 1 名」和「第 3 名」揉成一个平均数，需要条数才能定位")
        void countsTopOneOnlyAtRankOne() {
            RagEvalResult.MetricSummary summary = runner.summarize(
                    List.of(outcome(1, 1.0D), outcome(2, 1.0D), outcome(4, 1.0D)));

            assertThat(summary.getTopOneHits()).isEqualTo(1);
            assertThat(summary.getMrr())
                    .as("三条都命中了，MRR 却不低也不高 —— 单看它看不出「只有一条排首位」")
                    .isCloseTo((1.0D + 0.5D + 0.25D) / 3, within(1e-9));
        }
    }

    @Nested
    @DisplayName("报告渲染")
    class Report {

        @Test
        @DisplayName("分组对照要给出 MRR 与 Top-1 —— 语义型的短板全在排名上，只印 Recall 看不出来")
        void printsRankingMetricsPerGroup() {
            RagEvalResult result = new RagEvalResult();
            result.setSetName("代码检索召回评测集");
            result.setSetVersion("v1.0");
            result.setTotalCases(16);
            result.setTopK(8);
            result.setMaxChunksPerFile(3);
            result.setEmbeddingAvailable(true);

            RagEvalResult.ModeResult mode = new RagEvalResult.ModeResult();
            mode.setModeName("混合");
            mode.setVectorWeight(0.7D);
            mode.setKeywordWeight(0.3D);
            mode.setOverall(summary(16, 15, 11, 0.938D, 0.786D));
            mode.setPointed(summary(8, 8, 7, 1.0D, 0.938D));
            mode.setSemantic(summary(8, 7, 4, 0.875D, 0.635D));
            mode.setVectorSignalCases(16);
            mode.setKeywordSilentCases(2);
            result.getModes().add(mode);

            String report = result.toReadableReport();

            assertThat(report).contains("点名型").contains("语义型");
            assertThat(report)
                    .as("Top-1 要连条数一起给：50.0% 与「4/8」给人的行动感不一样")
                    .contains("50.0%（4/8）");
            assertThat(report)
                    .as("0.635 这样的 MRR 与 87.5% 的召回并列才能看出「捞到了但排得靠后」")
                    .contains("0.635");
            assertThat(report)
                    .as("向量是否参与打分要给出「关键词分 0 却排第一」的条数 —— "
                            + "向量分 > 0 是必然成立的，拿它当证据等于没测")
                    .contains("2/16 条用例的 top-1 关键词分为 0");
        }

        @Test
        @DisplayName("报告头必须自证折扣系数 —— 否则两批数字能不能直接比就成了悬案")
        void printsGenericFilePenalty() {
            RagEvalResult result = new RagEvalResult();
            result.setSetName("代码检索召回评测集");
            result.setSetVersion("v1.1");
            result.setTotalCases(28);
            result.setTopK(8);
            result.setMaxChunksPerFile(3);

            assertThat(result.toReadableReport())
                    .as("关闭时要写明「关闭」，而不是只印一个 1.0 —— "
                            + "「1.0 是本来就没打折，还是被打折后又凑回 1.0」不该留给读报告的人去猜")
                    .contains("通用文件折扣=1.0（关闭）");

            result.setGenericFilePenalty(0.9D);
            assertThat(result.toReadableReport())
                    .as("开着时必须显示实际系数：第四轮实测就是报告里看不出折扣开着，"
                            + "只能去翻 application.yml 才知道 Recall 为什么掉了 7 个百分点")
                    .contains("通用文件折扣=0.90");
        }

        @Test
        @DisplayName("向量权重为 0 的配置不印「向量分 > 0」—— 它算得出来但没进总分，印了等于说向量参与过")
        void keywordOnlyModeDoesNotClaimVectorTookPart() {
            RagEvalResult result = new RagEvalResult();
            result.setSetName("代码检索召回评测集");
            result.setSetVersion("v1.0");
            result.setTotalCases(16);
            result.setTopK(8);
            result.setMaxChunksPerFile(3);
            result.getModes().add(modeOf("仅关键词(0/1)", 0.0D, 1.0D, 16, 0));

            String report = result.toReadableReport();

            assertThat(report)
                    .as("该组 16 条用例的向量分确实都 > 0，但那 16 条的分根本没进总分 —— "
                            + "照实印出来就被读成「向量参与了排序」")
                    .contains("不适用：本组向量权重为 0");
            assertThat(report)
                    .as("这正是唯一一组向量没参与的配置，最不该出现这行")
                    .doesNotContain("16/16 条用例的 top-1 片段向量分 > 0");
        }

        @Test
        @DisplayName("关键词权重为 0 的配置不印关键词分的条数 —— 该组 16 条榜首全由向量决定，印 4 条反而暗示其余靠关键词")
        void vectorOnlyModeDoesNotImplyKeywordCarriedTheRest() {
            RagEvalResult result = new RagEvalResult();
            result.setSetName("代码检索召回评测集");
            result.setSetVersion("v1.0");
            result.setTotalCases(16);
            result.setTopK(8);
            result.setMaxChunksPerFile(3);
            result.getModes().add(modeOf("仅向量(1/0)", 1.0D, 0.0D, 16, 4));

            String report = result.toReadableReport();

            assertThat(report)
                    .as("向量权重非 0，链路检查照常给")
                    .contains("链路检查：16/16");
            assertThat(report)
                    .as("本组榜首无一例外由向量决定，逐条统计没有区分度，数字只会误导")
                    .doesNotContain("4/16 条用例的 top-1 关键词分为 0");
            assertThat(report).contains("本组关键词权重为 0");
        }

        @Test
        @DisplayName("排后清单按「榜首被谁占走」汇总 —— 该治的是那个文件，不是这批用例")
        void aggregatesWhoStoleTheTopSpot() {
            RagEvalResult result = new RagEvalResult();
            result.setSetName("代码检索召回评测集");
            result.setSetVersion("v1.1");
            result.setTotalCases(5);
            result.setTopK(8);
            result.setMaxChunksPerFile(3);

            RagEvalResult.ModeResult mode = modeOf("混合", 0.7D, 0.3D, 5, 0);
            mode.setCases(List.of(
                    outcomeOf("tour-navbar-semantic-05", 2, MAIN_CSS, NAVBAR),
                    outcomeOf("tour-home-semantic-06", 3, MAIN_CSS, GUIDE, HOME),
                    outcomeOf("shop-app-semantic-13", 2, ABOUT, "src/App.vue"),
                    outcomeOf("shop-story-semantic-26", 1, ABOUT),
                    outcomeOf("shop-detail-semantic-16", 0, "src/data/products.js")));
            result.getModes().add(mode);

            String report = result.toReadableReport();

            assertThat(report)
                    .as("MRR 与 Top-1 只给一个数，看不出榜首究竟被谁占走；"
                            + "按占位文件汇总才能回答「该治哪个文件」")
                    .contains("排后 3 条，榜首占位：src/styles/main.css×2、src/pages/About.vue×1");
            assertThat(report)
                    .as("排首位的不算排后；漏召的也不算 —— 它连期望文件都没进 Top-K，"
                            + "没有「被挤到第几名」这回事。把这两类混进来就分不清该补检索还是该治通用文件")
                    .doesNotContain("排后 4 条")
                    .doesNotContain("排后 5 条");
        }

        @Test
        @DisplayName("漏召清单要给出用例号 —— 只看得到「谁漏了」才定位得了「是谁不行」")
        void listsMissedCaseIdsPerMode() {
            RagEvalResult result = new RagEvalResult();
            result.setSetName("代码检索召回评测集");
            result.setSetVersion("v1.0");
            result.setTotalCases(2);
            result.setTopK(8);
            result.setMaxChunksPerFile(3);

            RagEvalResult.ModeResult mode =
                    modeOf("仅关键词(0/1)", 0.0D, 1.0D, 2, 0);
            mode.setCases(List.of(outcomeOf("tour-navbar-semantic-05", 1),
                    outcomeOf("shop-detail-semantic-16", 0)));
            result.getModes().add(mode);

            String report = result.toReadableReport();

            assertThat(report)
                    .as("只给百分比看不出是哪几条在拖后腿；跨配置对照用例号才看得出「谁靠谁」")
                    .contains("漏召 1 条：shop-detail-semantic-16");
            assertThat(report)
                    .as("漏召的用例号里不应混进命中的那条")
                    .doesNotContain("tour-navbar-semantic-05、");
        }
    }

    @Nested
    @DisplayName("一轮评测编排")
    class Run {

        @Test
        @DisplayName("要数出「top-1 关键词分为 0」的用例数 —— 向量分 > 0 必然成立，只有这个数能证明向量在起作用")
        void countsKeywordSilentCases() {
            RagEvalSetLoader loader = mock(RagEvalSetLoader.class);
            when(loader.load()).thenReturn(evalSet());
            ReflectionTestUtils.setField(runner, "ragEvalSetLoader", loader);
            ReflectionTestUtils.setField(runner, "codeIndexService", mock(CodeIndexService.class));
            ReflectionTestUtils.setField(runner, "ragProperties", new RagProperties());
            ReflectionTestUtils.setField(runner, "embeddingClient", embeddingClient());

            // 第一条：榜首关键词分为 0 —— 它排第一只可能是向量推上去的
            // 第二条：榜首关键词分 0.6 —— 字面命中就足以解释，说明不了向量有没有用
            when(codeRetriever.retrieve(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                    .thenReturn(List.of(new RetrievedSlice(NAVBAR, 1, 20, "//c", 0.9D, 0.78D, 0D)))
                    .thenReturn(List.of(new RetrievedSlice(HOME, 1, 20, "//c", 0.9D, 0.83D, 0.6D)));

            RagEvalResult result = runner.run(false);

            RagEvalResult.ModeResult mode = result.getModes().get(0);
            assertThat(mode.getKeywordSilentCases())
                    .as("两条用例共用一个「向量分 > 0」的必然事实，只有关键词分为 0 的条数能把它们区分开")
                    .isEqualTo(1);
            assertThat(mode.getVectorSignalCases()).isEqualTo(2);
        }
    }

    private static EmbeddingClient embeddingClient() {
        EmbeddingClient client = mock(EmbeddingClient.class);
        when(client.available()).thenReturn(true);
        return client;
    }

    private static RagEvalSet evalSet() {
        RagEvalSet set = new RagEvalSet();
        set.setName("代码检索召回评测集");
        set.setVersion("v1.0");
        set.setApps(List.of(evalApp()));
        set.setCases(List.of(evalCase(NAVBAR), evalCase(HOME)));
        return set;
    }

    /**
     * 造一组完整的配置结果。三份汇总都要给上 —— 报告渲染会无条件遍历它们，
     * 漏一个就是 NPE，而不是「这块不印」。
     */
    private static RagEvalResult.ModeResult modeOf(String name, double vectorWeight,
                                                   double keywordWeight,
                                                   int vectorSignalCases,
                                                   int keywordSilentCases) {
        RagEvalResult.ModeResult mode = new RagEvalResult.ModeResult();
        mode.setModeName(name);
        mode.setVectorWeight(vectorWeight);
        mode.setKeywordWeight(keywordWeight);
        mode.setOverall(summary(16, 15, 11, 0.938D, 0.786D));
        mode.setPointed(summary(8, 8, 8, 1.0D, 1.0D));
        mode.setSemantic(summary(8, 7, 3, 0.875D, 0.573D));
        mode.setVectorSignalCases(vectorSignalCases);
        mode.setKeywordSilentCases(keywordSilentCases);
        return mode;
    }

    /**
     * 造一条只有用例号与名次的用例结果，供清单类渲染用。
     *
     * <p>期望文件也一并填上 —— 缺了它会让人在做反向验证时红出一个
     * {@code IndexOutOfBounds}：那种红同样来自「改错了地方」，但看起来像数据不全，
     * 分不清是哪一处决定导致的，属于假信号。</p>
     */
    private static RagEvalResult.CaseOutcome outcomeOf(String caseId, int firstHitRank,
                                                      String... retrievedFiles) {
        RagEvalResult.CaseOutcome outcome = new RagEvalResult.CaseOutcome();
        outcome.setCaseId(caseId);
        outcome.setFirstHitRank(firstHitRank);
        outcome.setExpectedFiles(List.of(NAVBAR));
        outcome.setRetrievedFiles(List.of(retrievedFiles));
        return outcome;
    }

    private static RagEvalResult.MetricSummary summary(int cases, int hits, int topOne,
                                                      double recall, double mrr) {
        RagEvalResult.MetricSummary summary = new RagEvalResult.MetricSummary();
        summary.setCases(cases);
        summary.setHits(hits);
        summary.setTopOneHits(topOne);
        summary.setHitRate((double) hits / cases);
        summary.setRecallAtK(recall);
        summary.setPrecisionAtK(recall);
        summary.setMrr(mrr);
        return summary;
    }

    private static RagEvalResult.CaseOutcome outcome(int firstHitRank, double recall) {
        RagEvalResult.CaseOutcome outcome = new RagEvalResult.CaseOutcome();
        outcome.setFirstHitRank(firstHitRank);
        outcome.setRecall(recall);
        outcome.setPrecision(recall);
        outcome.setReciprocalRank(firstHitRank > 0 ? 1.0D / firstHitRank : 0D);
        return outcome;
    }

    private void stubRetrieval(RetrievedSlice... slices) {
        when(codeRetriever.retrieve(anyLong(), any(CodeGenTypeEnum.class), anyString()))
                .thenReturn(List.of(slices));
    }

    private static RetrievedSlice slice(String filePath, double score) {
        return new RetrievedSlice(filePath, 1, 20, "// code", score, score, 0D);
    }

    private static RagEvalCase evalCase(String... expectedFiles) {
        RagEvalCase evalCase = new RagEvalCase();
        evalCase.setId("tour-navbar-01");
        evalCase.setAppKey("tour");
        evalCase.setKind(RetrievalKind.SEMANTIC);
        evalCase.setQuery("页面向下滚动之后，顶上那条导航栏的背景要变成不透明的白色");
        evalCase.setExpectedFiles(List.of(expectedFiles));
        return evalCase;
    }

    private static RagEvalApp evalApp() {
        RagEvalApp app = new RagEvalApp();
        app.setKey("tour");
        app.setLabel("云雾山旅游站");
        app.setAppId(APP_ID);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT);
        return app;
    }
}
