package com.quan.aicodehelper.core.startup;

import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.core.rag.EmbeddingClient;
import com.quan.aicodehelper.core.startup.RagEmbeddingChecker.CheckResult;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RAG embedding 启动自检测试。{@link EmbeddingClient} 换成桩，专测结论推导与异常翻译，
 * 不跑真实网络。两条断言是这套检查存在的理由：配置不可用时绝不能发起真实请求，
 * 跳过时绝不能触碰客户端。
 */
@DisplayName("RAG embedding 启动自检")
class RagEmbeddingCheckerTest {

    private RagProperties properties;
    private EmbeddingClient embeddingClient;
    private RagEmbeddingChecker checker;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.init();
        embeddingClient = mock(EmbeddingClient.class);
        checker = new RagEmbeddingChecker(properties, embeddingClient);
    }

    private void stubProbe(EmbeddingClient.Probe.Kind kind, int dimension, long costMs,
                           String detail, Throwable error) {
        when(embeddingClient.probe())
                .thenReturn(new EmbeddingClient.Probe(kind, dimension, costMs, detail, error));
    }

    @Nested
    @DisplayName("跳过条件")
    class Skip {

        @Test
        @DisplayName("RAG 总开关关闭时应直接跳过，且完全不碰客户端")
        void skipsWhenRagDisabled() {
            properties.setEnabled(false);

            CheckResult result = checker.check();

            assertThat(result.skipped()).isTrue();
            assertThat(result.ok())
                    .as("跳过不等于失败 —— 汇总时若把它当失败，用户会被无谓地打扰")
                    .isTrue();
            verifyNoInteractions(embeddingClient);
        }

        @Test
        @DisplayName("单独关掉自检开关时也应跳过")
        void skipsWhenSelfCheckDisabled() {
            properties.getEmbedding().setSelfCheck(false);

            CheckResult result = checker.check();

            assertThat(result.skipped()).isTrue();
            verifyNoInteractions(embeddingClient);
        }

        @Test
        @DisplayName("两个开关都在时自检才生效")
        void activeOnlyWhenBothEnabled() {
            assertThat(checker.selfCheckActive()).isTrue();

            properties.getEmbedding().setSelfCheck(false);
            assertThat(checker.selfCheckActive()).isFalse();

            properties.getEmbedding().setSelfCheck(true);
            properties.setEnabled(false);
            assertThat(checker.selfCheckActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("结论推导")
    class Conclusion {

        @Test
        @DisplayName("配置不可用时应在本地判定并给出原因，不必真发一次请求")
        void configProblemIsDecidedLocally() {
            when(embeddingClient.configProblem())
                    .thenReturn("base-url 仍是未替换的占位符 ${LLM_BASE_URL} —— 环境变量没有注入到运行环境");

            CheckResult result = checker.check();

            assertThat(result.ok()).isFalse();
            assertThat(result.title()).isEqualTo("配置不可用");
            assertThat(result.reason())
                    .as("这句是给「在 IDE 里跑得通、命令行里跑不通」的人看的，必须保留原因原文")
                    .contains("环境变量");
            verify(embeddingClient, never()).probe();
        }

        @Test
        @DisplayName("探针成功时应透传维度与耗时 —— 维度是判断模型是否被静默替换的唯一线索")
        void successCarriesDimension() {
            when(embeddingClient.configProblem()).thenReturn(null);
            stubProbe(EmbeddingClient.Probe.Kind.OK, 1024, 168L, null, null);

            CheckResult result = checker.check();

            assertThat(result.ok()).isTrue();
            assertThat(result.dimension()).isEqualTo(1024);
            assertThat(result.costMs()).isEqualTo(168L);
        }

        @Test
        @DisplayName("探针自己带配置结论时，以它的结论为准")
        void probeProblemWins() {
            when(embeddingClient.configProblem()).thenReturn(null);
            stubProbe(EmbeddingClient.Probe.Kind.CONFIG_PROBLEM, 0, 0L, "model-name 为空", null);

            CheckResult result = checker.check();

            assertThat(result.title()).isEqualTo("配置不可用");
            assertThat(result.reason()).isEqualTo("model-name 为空");
        }

        @Test
        @DisplayName("配置没问题但调用失败时，应把异常翻译成排查方向")
        void callFailureIsTranslated() {
            when(embeddingClient.configProblem()).thenReturn(null);
            stubProbe(EmbeddingClient.Probe.Kind.CALL_FAILURE, 0, 0L, null,
                    new AuthenticationException("401"));

            CheckResult result = checker.check();

            assertThat(result.title()).isEqualTo("调用失败");
            assertThat(result.reason()).contains("api-key");
        }

        @Test
        @DisplayName("被预算闸门拒绝时必须单独报出来，不能混成「调用失败」")
        void budgetBlockedIsItsOwnConclusion() {
            when(embeddingClient.configProblem()).thenReturn(null);
            stubProbe(EmbeddingClient.Probe.Kind.BUDGET_BLOCKED, 0, 0L,
                    "AI 调用次数已达上限（20 次），请重启服务", null);

            CheckResult result = checker.check();

            assertThat(result.title())
                    .as("报成「调用失败」会让人去查网络和 key，而这两者此刻都是好的")
                    .isEqualTo("预算已耗尽");
            assertThat(result.reason())
                    .as("额度问题必须原样带出闸门给的原因，否则用户不知道该调哪个数")
                    .contains("上限");
            assertThat(result.hint())
                    .as("这类问题最容易被误诊，提示里必须先把「不是 key 的问题」排除掉")
                    .contains("key")
                    .contains("max-calls");
        }

        @Test
        @DisplayName("三种失败给出的下一步动作必须各不相同 —— 建议要跟着结论走")
        void eachFailureKindCarriesItsOwnAdvice() {
            when(embeddingClient.configProblem()).thenReturn(null);

            stubProbe(EmbeddingClient.Probe.Kind.CONFIG_PROBLEM, 0, 0L, "model-name 为空", null);
            String configHint = checker.check().hint();

            stubProbe(EmbeddingClient.Probe.Kind.BUDGET_BLOCKED, 0, 0L, "额度已满", null);
            String budgetHint = checker.check().hint();

            stubProbe(EmbeddingClient.Probe.Kind.CALL_FAILURE, 0, 0L, null,
                    new AuthenticationException("401"));
            String callHint = checker.check().hint();

            assertThat(configHint).isNotEqualTo(budgetHint);
            assertThat(budgetHint).isNotEqualTo(callHint);
            assertThat(callHint).isNotEqualTo(configHint);
        }
    }

    @Nested
    @DisplayName("异常翻译")
    class ExceptionTranslation {

        @Test
        @DisplayName("401 要指向 key，而不是含糊地说「调用失败」")
        void authenticationPointsToApiKey() {
            assertThat(RagEmbeddingChecker.describe(new AuthenticationException("401")))
                    .contains("api-key");
        }

        @Test
        @DisplayName("404 要指向模型名与开通状态")
        void modelNotFoundPointsToModelName() {
            assertThat(RagEmbeddingChecker.describe(new ModelNotFoundException("404")))
                    .contains("model-name")
                    .contains("开通");
        }

        @Test
        @DisplayName("429 要指向额度")
        void rateLimitPointsToQuota() {
            assertThat(RagEmbeddingChecker.describe(new RateLimitException("429")))
                    .contains("额度");
        }

        @Test
        @DisplayName("超时要指向网络，而不是让人去换 key")
        void timeoutPointsToNetwork() {
            assertThat(RagEmbeddingChecker.describe(new TimeoutException("timeout")))
                    .contains("超时");
        }

        @Test
        @DisplayName("地址解析失败要指向 base-url")
        void unresolvedHostPointsToBaseUrl() {
            assertThat(RagEmbeddingChecker.describe(new UnresolvedModelServerException("resolve")))
                    .contains("base-url");
        }

        @Test
        @DisplayName("其它 HTTP 状态码应带出码值与报文")
        void otherHttpStatusKeepsCode() {
            assertThat(RagEmbeddingChecker.describe(new HttpException(500, "internal boom")))
                    .contains("500")
                    .contains("internal boom");
        }

        @Test
        @DisplayName("未归类的异常要带上原类型与消息，不能丢掉排查线索")
        void unknownExceptionKeepsTypeAndMessage() {
            assertThat(RagEmbeddingChecker.describe(new IllegalStateException("boom")))
                    .contains("IllegalStateException")
                    .contains("boom");
        }

        @Test
        @DisplayName("异常为 null 时也要给出可读结论")
        void nullErrorIsHandled() {
            assertThat(RagEmbeddingChecker.describe(null)).contains("未知错误");
        }
    }
}
