package com.quan.aicodehelper.core.rag;

import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * embedding 客户端配置体检测试。Spring 绑定 {@code @ConfigurationProperties} 时占位符解析器带
 * {@code ignoreUnresolvablePlaceholders=true}，环境变量缺失时 {@code ${LLM_BASE_URL}} 会原样保留成
 * 字面量，{@code available()} 因此误报 true，关键断言是它必须判为不可用。
 */
@DisplayName("embedding 客户端")
class EmbeddingClientTest {

    private RagProperties properties;
    private EmbeddingClient client;
    private LlmCallGuard budgetGuard;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.init();
        budgetGuard = mock(LlmCallGuard.class);
        client = new EmbeddingClient();
        ReflectionTestUtils.setField(client, "ragProperties", properties);
        // 探针也要过预算闸门，不注入会空指针；这条依赖关系本身也是被测内容
        ReflectionTestUtils.setField(client, "llmCallGuard", budgetGuard);
    }

    private void configure(String baseUrl, String apiKey) {
        properties.getEmbedding().setBaseUrl(baseUrl);
        properties.getEmbedding().setApiKey(apiKey);
    }

    @Nested
    @DisplayName("占位符识别")
    class PlaceholderDetection {

        @Test
        @DisplayName("只有完整包裹的 ${...} 才算未替换的占位符")
        void detectsUnresolvedPlaceholder() {
            assertThat(EmbeddingClient.isUnresolvedPlaceholder("${LLM_BASE_URL}"))
                    .as("这正是环境变量没注入时 Spring 留下的字面量")
                    .isTrue();
        }

        @Test
        @DisplayName("正常地址、空值、半截式子都不应误判")
        void doesNotMisjudgeNormalValues() {
            assertThat(EmbeddingClient.isUnresolvedPlaceholder("https://x.com/v1")).isFalse();
            assertThat(EmbeddingClient.isUnresolvedPlaceholder(null)).isFalse();
            assertThat(EmbeddingClient.isUnresolvedPlaceholder("")).isFalse();
            assertThat(EmbeddingClient.isUnresolvedPlaceholder("abc${A}")).isFalse();
            assertThat(EmbeddingClient.isUnresolvedPlaceholder("$A}")).isFalse();
        }
    }

    @Nested
    @DisplayName("配置体检")
    class ConfigCheck {

        @Test
        @DisplayName("配置齐备时应通过体检，不该无病呻吟")
        void completeConfigPasses() {
            configure("https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-whatever");

            assertThat(client.configProblem()).isNull();
        }

        @Test
        @DisplayName("base-url 是未注入的占位符时必须判为不可用 —— 否则每次检索都会打到一个假地址")
        void unresolvedBaseUrlIsNotUsable() {
            configure("${LLM_BASE_URL}", "sk-whatever");

            assertThat(client.configProblem())
                    .as("必须点明「环境变量没注入」这个真正的原因，否则只会让人去查 key")
                    .contains("占位符")
                    .contains("环境变量");
            assertThat(client.available())
                    .as("字面量占位符非空，只判空的实现会在这里返回 true，"
                            + "于是向量检索表面「已参与打分」，实际每一次都失败")
                    .isFalse();
        }

        @Test
        @DisplayName("api-key 是未注入的占位符时同样要能报出来")
        void unresolvedApiKeyIsNotUsable() {
            configure("https://x.com/v1", "${LLM_API_KEY}");

            assertThat(client.configProblem()).contains("api-key").contains("环境变量");
            assertThat(client.available()).isFalse();
        }

        @Test
        @DisplayName("空值要指名道姓说清是哪个字段为空")
        void blankValuesAreReportedPerField() {
            configure("   ", "sk-whatever");
            assertThat(client.configProblem()).isEqualTo("base-url 为空");

            configure("https://x.com/v1", "");
            assertThat(client.configProblem()).isEqualTo("api-key 为空");

            configure("https://x.com/v1", "sk-whatever");
            properties.getEmbedding().setModelName("");
            assertThat(client.configProblem())
                    .as("模型名为空会退化到服务商默认模型，收敛不到预期的语义空间")
                    .isEqualTo("model-name 为空");
        }

        @Test
        @DisplayName("整段配置缺失也要给结论，不能抛空指针")
        void missingSectionIsReported() {
            properties.setEmbedding(null);

            assertThat(client.configProblem()).isEqualTo("app.rag.embedding 整段未配置");
        }
    }

    @Nested
    @DisplayName("自检探针")
    class ProbeTest {

        @Test
        @DisplayName("配置不可用时探针只给原因、不抛异常，也不会真发请求")
        void probeReportsProblemWithoutThrowing() {
            configure("${LLM_BASE_URL}", "sk-whatever");

            EmbeddingClient.Probe probe = client.probe();

            assertThat(probe.ok()).isFalse();
            assertThat(probe.kind()).isEqualTo(EmbeddingClient.Probe.Kind.CONFIG_PROBLEM);
            assertThat(probe.detail())
                    .as("自检的价值是给出结论，异常本身就是结论的一部分")
                    .contains("占位符");
            assertThat(probe.error()).isNull();
            assertThat(probe.dimension()).isZero();
        }

        @Test
        @DisplayName("探针要记账，且被闸门拒绝时必须自成一类")
        void budgetRejectionIsItsOwnKind() {
            configure("https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-whatever");
            // 让闸门拒绝而不是放行：放行会真的去连服务商，测试就依赖网络了
            doThrow(new BusinessException(ErrorCode.OPERATION_ERROR, "AI 调用次数已达上限（20 次）"))
                    .when(budgetGuard).acquire(anyString());

            EmbeddingClient.Probe probe = client.probe();

            // 自检发的也是一次真实 embedding 请求，同样要走账本
            verify(budgetGuard).acquire("RAG 自检");
            // 被拒绝要单独成一类：配置问题改配置、调用失败查网络、被拒绝调额度，
            // 混在一起会把额度问题误报成 key 无效
            assertThat(probe.kind())
                    .as("配置是对的、请求也没发出去，归进另外两类都会把人引向错误的排查方向")
                    .isEqualTo(EmbeddingClient.Probe.Kind.BUDGET_BLOCKED);
            assertThat(probe.detail()).contains("上限");
            assertThat(probe.error())
                    .as("请求根本没发出去，就不该有调用异常 —— 有的话说明这条路径已经跑到真实请求上了")
                    .isNull();
        }
    }
}
