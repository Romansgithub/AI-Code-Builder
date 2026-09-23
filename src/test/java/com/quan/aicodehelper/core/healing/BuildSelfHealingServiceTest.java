package com.quan.aicodehelper.core.healing;

import com.quan.aicodehelper.ai.factory.AiCodeGeneratorServiceFactory;
import com.quan.aicodehelper.ai.service.AiCodeHealingService;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.config.LlmBudgetProperties;
import com.quan.aicodehelper.config.SelfHealingProperties;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.builder.VueProjectBuilder;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 构建自愈循环测试：mock 掉构建器与 AI 服务，专注验证重试决策逻辑。
 * mock 的是 {@link AiCodeHealingService} 而非生成服务，后者带「用户输入 ≤1000 字」护栏。
 */
@DisplayName("构建自愈服务")
class BuildSelfHealingServiceTest {

    private static final Long APP_ID = 1001L;
    private static final String PROJECT_PATH = "/tmp/fake-project";

    private VueProjectBuilder vueProjectBuilder;
    private AiCodeGeneratorServiceFactory serviceFactory;
    private AiCodeHealingService healingService;
    private GenerationProgressRegistry progressRegistry;
    private BuildSelfHealingService service;
    private LlmCallGuard llmCallGuard;

    /** 记录推送给前端的进度消息，便于断言用户能看到什么 */
    private final List<String> emittedMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        vueProjectBuilder = mock(VueProjectBuilder.class);
        serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        healingService = mock(AiCodeHealingService.class);
        progressRegistry = mock(GenerationProgressRegistry.class);

        when(serviceFactory.getAiCodeHealingService(anyLong()))
                .thenReturn(healingService);
        // AI 调用返回一句简短说明即可，自愈逻辑不关心内容
        when(healingService.healVueProjectBuildFailure(anyLong(), anyString()))
                .thenReturn(Flux.just("已修复"));

        org.mockito.Mockito.doAnswer(inv -> {
            emittedMessages.add(inv.getArgument(1));
            return null;
        }).when(progressRegistry).emit(anyLong(), anyString());

        SelfHealingProperties props = new SelfHealingProperties();
        props.setEnabled(true);
        props.setMaxAttempts(2);
        props.setMaxErrorLength(3000);
        props.init();

        service = new BuildSelfHealingService();
        ReflectionTestUtils.setField(service, "vueProjectBuilder", vueProjectBuilder);
        ReflectionTestUtils.setField(service, "aiCodeGeneratorServiceFactory", serviceFactory);
        ReflectionTestUtils.setField(service, "selfHealingProperties", props);
        ReflectionTestUtils.setField(service, "generationProgressRegistry", progressRegistry);

        // 上限充足的真实闸门；预算行为在专属嵌套类里单独验证
        LlmBudgetProperties budgetProps = new LlmBudgetProperties();
        budgetProps.setMaxCalls(1000);
        llmCallGuard = new LlmCallGuard(budgetProps);
        ReflectionTestUtils.setField(service, "llmCallGuard", llmCallGuard);
    }

    private BuildResult compileError() {
        return BuildResult.fail(BuildFailureTypeEnum.COMPILE_ERROR, "项目构建",
                "error: Cannot find module './Missing.vue'", "raw output");
    }

    private void verifyHealCalls(int expected) {
        verify(healingService, times(expected))
                .healVueProjectBuildFailure(anyLong(), anyString());
    }

    /** 取最后一次传给模型的 user 消息（即构建失败报告） */
    private String captureLastReport() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(healingService, org.mockito.Mockito.atLeastOnce())
                .healVueProjectBuildFailure(anyLong(), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("首次构建成功")
    class FirstBuildSucceeds {

        @Test
        @DisplayName("不应触发任何 AI 修复调用")
        void shouldNotCallAi() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(BuildResult.ok("ok"));

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isTrue();
            verify(healingService, never()).healVueProjectBuildFailure(anyLong(), anyString());
        }

        @Test
        @DisplayName("应推送构建成功提示")
        void shouldEmitSuccess() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(BuildResult.ok("ok"));

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(emittedMessages).anyMatch(m -> m.contains("构建成功"));
        }
    }

    @Nested
    @DisplayName("不可自愈的失败")
    class NotHealable {
        @Test
        @DisplayName("依赖安装失败时绝不应调用 AI —— 改代码解决不了环境问题")
        void dependencyFailureShouldNotCallAi() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(BuildResult.fail(BuildFailureTypeEnum.DEPENDENCY_INSTALL,
                            "依赖安装", "npm ERR! network", "raw"));

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isFalse();
            verify(healingService, never()).healVueProjectBuildFailure(anyLong(), anyString());
            // 只构建一次，不重试
            verify(vueProjectBuilder, times(1)).buildProjectWithResult(anyString(), anyLong());
        }

        @Test
        @DisplayName("超时同样不应触发修复")
        void timeoutShouldNotCallAi() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(BuildResult.fail(BuildFailureTypeEnum.TIMEOUT,
                            "项目构建", "构建超时", "raw"));

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            verify(healingService, never()).healVueProjectBuildFailure(anyLong(), anyString());
        }

        @Test
        @DisplayName("应把失败原因推送给前端，而不是静默失败")
        void shouldEmitFailureReason() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(BuildResult.fail(BuildFailureTypeEnum.DEPENDENCY_INSTALL,
                            "依赖安装", "npm ERR! network", "raw"));

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(emittedMessages).anyMatch(m -> m.contains("依赖安装失败"));
        }
    }

    @Nested
    @DisplayName("可自愈的失败")
    class Healable {

        @Test
        @DisplayName("首轮修复后成功，应只调用一次 AI 并返回成功")
        void shouldHealOnFirstAttempt() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError(), BuildResult.ok("rebuilt"));

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isTrue();
            verifyHealCalls(1);
            // 初次构建 + 修复后构建 = 2 次
            verify(vueProjectBuilder, times(2)).buildProjectWithResult(anyString(), anyLong());
        }

        @Test
        @DisplayName("首轮失败、次轮成功，应调用两次 AI")
        void shouldHealOnSecondAttempt() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError(), compileError(), BuildResult.ok("rebuilt"));

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isTrue();
            verifyHealCalls(2);
        }

        @Test
        @DisplayName("用尽重试仍失败时，不应超过 maxAttempts 次 AI 调用")
        void shouldStopAtMaxAttempts() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isFalse();
            verifyHealCalls(2);
            // 初次构建 + 2 次重试 = 3 次
            verify(vueProjectBuilder, times(3)).buildProjectWithResult(anyString(), anyLong());
        }

        @Test
        @DisplayName("用尽重试后，错误信息应包含各轮失败原因便于排查")
        void shouldAggregateAttemptErrors() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.getErrorMessage())
                    .contains("已自动尝试修复 2 次")
                    .contains("第 1 轮")
                    .contains("第 2 轮");
        }

        @Test
        @DisplayName("修复过程中失败类型变为不可自愈时，应立即终止重试")
        void shouldAbortWhenFailureBecomesNotHealable() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError(),
                            BuildResult.fail(BuildFailureTypeEnum.DEPENDENCY_INSTALL,
                                    "依赖安装", "npm ERR! network", "raw"));

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureType()).isEqualTo(BuildFailureTypeEnum.DEPENDENCY_INSTALL);
            // 只调了一次 AI，第二轮发现不可自愈就停了
            verifyHealCalls(1);
        }

        @Test
        @DisplayName("应推送带轮次的修复进度，让用户看到实时状态")
        void shouldEmitProgressWithAttempt() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError(), BuildResult.ok("rebuilt"));

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(emittedMessages).anyMatch(m -> m.contains("1/2"));
            assertThat(emittedMessages).anyMatch(m -> m.contains("自动修复成功"));
        }
    }

    @Nested
    @DisplayName("AI 调用失败后的重试决策")
    class AiCallFailure {

        @Test
        @DisplayName("基础设施类失败（限流）值得再试一轮")
        void shouldRetryOnInfrastructureFailure() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());
            when(healingService.healVueProjectBuildFailure(anyLong(), anyString()))
                    .thenThrow(new RateLimitException("429 Too Many Requests"));

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isFalse();
            // 限流换个时间点确实可能通，所以两轮都试了
            verifyHealCalls(2);
        }

        @Test
        @DisplayName("参数非法这类语义失败不重试 —— 换模型、换时间都是同样结果")
        void shouldNotRetryOnSemanticFailure() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());
            when(healingService.healVueProjectBuildFailure(anyLong(), anyString()))
                    .thenThrow(new InvalidRequestException("context length exceeded"));

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            verifyHealCalls(1);
        }

        @Test
        @DisplayName("确定性失败必须立刻中止，绝不能再试一轮")
        void shouldAbortImmediatelyOnDeterministicFailure() {
            // catch (Exception) 一律返回「这轮没修好」，语义在外层是 continue，
            // 于是任何确定性失败（输入校验、护栏拒绝、参数非法）都会把剩余轮次空转烧完。
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());
            when(healingService.healVueProjectBuildFailure(anyLong(), anyString()))
                    .thenThrow(new RuntimeException("输入校验未通过"));

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isFalse();
            verifyHealCalls(1);
            // 第二轮都不该被发起：构建也不该被重跑
            verify(vueProjectBuilder, times(1)).buildProjectWithResult(anyString(), anyLong());
        }

        @Test
        @DisplayName("中止时错误信息要说清「未继续重试」，不能与「试过 N 次都不行」混淆")
        void shouldExplainAbortInErrorMessage() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());
            when(healingService.healVueProjectBuildFailure(anyLong(), anyString()))
                    .thenThrow(new RuntimeException("输入校验未通过"));

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.getErrorMessage())
                    .contains("已中止")
                    .contains("未继续重试")
                    .contains("输入校验未通过");
        }

        @Test
        @DisplayName("中止的轮次必须计入 healingAttempts —— 否则失败样本会从自愈成功率里静默消失")
        void shouldCountAbortedAttempt() {
            // 返回 0 轮会让报告显示「本次无用例触发自愈」，而实情是自愈被触发后一个字都没改
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());
            when(healingService.healVueProjectBuildFailure(anyLong(), anyString()))
                    .thenThrow(new RuntimeException("输入校验未通过"));

            HealingOutcome outcome = service.buildWithHealingOutcome(PROJECT_PATH, APP_ID);

            assertThat(outcome.getHealingAttempts()).isEqualTo(1);
            assertThat(outcome.isFirstBuildPassed()).isFalse();
            assertThat(outcome.isFinalPassed()).isFalse();
        }

        @Test
        @DisplayName("中止时不该推送「继续重试」，那会让用户以为还在努力")
        void shouldNotEmitRetryMessageWhenAborted() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());
            when(healingService.healVueProjectBuildFailure(anyLong(), anyString()))
                    .thenThrow(new RuntimeException("输入校验未通过"));

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(emittedMessages).noneMatch(m -> m.contains("继续重试"));
            assertThat(emittedMessages).anyMatch(m -> m.contains("已中止"));
        }
    }

    @Nested
    @DisplayName("喂给模型的 user 消息")
    class PromptComposition {

        @Test
        @DisplayName("只装构建失败信息，不装修复指令 —— 指令属于 system 消息")
        void shouldNotPutInstructionsIntoUserMessage() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError(), BuildResult.ok("rebuilt"));

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            String report = captureLastReport();
            assertThat(report).contains("Cannot find module");
            // 「最小改动」「先诊断再动手」这类要求都在 system 提示词里
            assertThat(report)
                    .doesNotContain("最小改动")
                    .doesNotContain("修复要求")
                    .doesNotContain("先诊断再动手");
        }

        @Test
        @DisplayName("user 消息长度只随报错增长：超长报错必须被截断到 maxErrorLength 附近")
        void shouldTruncateOverlongErrorMessage() {
            // BuildResult.errorMessage 由不带长度上限的方法生成，maxErrorLength 原本不生效
            SelfHealingProperties props = new SelfHealingProperties();
            props.setEnabled(true);
            props.setMaxAttempts(1);
            props.setMaxErrorLength(200);
            props.init();
            ReflectionTestUtils.setField(service, "selfHealingProperties", props);

            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(BuildResult.fail(BuildFailureTypeEnum.COMPILE_ERROR, "项目构建",
                                    "x".repeat(5000), "raw"),
                            BuildResult.ok("rebuilt"));

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            String report = captureLastReport();
            assertThat(report).hasSizeLessThanOrEqualTo(
                    props.getMaxErrorLength() + BuildSelfHealingService.PROMPT_OVERHEAD_RESERVE);
            assertThat(report).contains("截断");
        }

        @Test
        @DisplayName("报错为空时应给出可读兜底文案，而不是把 null 喂给模型")
        void shouldFallbackWhenErrorIsBlank() {
            SelfHealingProperties props = new SelfHealingProperties();
            props.setEnabled(true);
            props.setMaxAttempts(1);
            props.setMaxErrorLength(3000);
            props.init();
            ReflectionTestUtils.setField(service, "selfHealingProperties", props);

            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(BuildResult.fail(BuildFailureTypeEnum.COMPILE_ERROR, "项目构建",
                                    null, ""),
                            BuildResult.ok("rebuilt"));

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            String report = captureLastReport();
            assertThat(report).contains("未能提取到具体错误信息").doesNotContain("null");
        }
    }

    @Nested
    @DisplayName("自愈开关")
    class Toggle {

        @Test
        @DisplayName("关闭自愈时，即使失败可自愈也不调用 AI")
        void disabledShouldNotHeal() {
            SelfHealingProperties props = new SelfHealingProperties();
            props.setEnabled(false);
            props.setMaxAttempts(2);
            props.init();
            ReflectionTestUtils.setField(service, "selfHealingProperties", props);

            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            verify(healingService, never()).healVueProjectBuildFailure(anyLong(), anyString());
            verify(vueProjectBuilder, times(1)).buildProjectWithResult(anyString(), anyLong());
        }

        @Test
        @DisplayName("maxAttempts 为 0 等价于关闭自愈")
        void zeroAttemptsShouldNotHeal() {
            SelfHealingProperties props = new SelfHealingProperties();
            props.setEnabled(true);
            props.setMaxAttempts(0);
            props.init();
            ReflectionTestUtils.setField(service, "selfHealingProperties", props);

            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            verify(healingService, never()).healVueProjectBuildFailure(anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("LLM 预算耗尽")
    class BudgetExhausted {

        /** 把闸门换成已耗尽的状态 */
        private void exhaustBudget() {
            LlmBudgetProperties props = new LlmBudgetProperties();
            props.setMaxCalls(1);
            LlmCallGuard exhausted = new LlmCallGuard(props);
            exhausted.acquire("先占满");
            ReflectionTestUtils.setField(service, "llmCallGuard", exhausted);
        }

        @Test
        @DisplayName("预算耗尽时必须立刻中止自愈循环，而不是当作「这轮没修好」继续重试")
        void shouldAbortLoopImmediately() {
            // askAiToFix 若吞掉预算异常并返回 false，外层语义是「继续下一轮」，
            // 会用已耗尽的预算在一次热循环里把剩余重试次数烧完，用户看到「一直在修复」却什么都没发生。
            exhaustBudget();
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isFalse();
            // AI 一次都不该被真正调用：预算在发起前就拦住了
            verify(healingService, never()).healVueProjectBuildFailure(anyLong(), anyString());
        }

        @Test
        @DisplayName("预算耗尽时不应把「重试中」的错误提示推给用户")
        void shouldNotEmitRetryMessage() {
            // 预算拦截不是「修复未生效」，不该谎报成「继续重试」
            exhaustBudget();
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());

            service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(emittedMessages).noneMatch(m -> m.contains("继续重试"));
        }

        @Test
        @DisplayName("预算拦下的轮次同样要计入 healingAttempts，不能从自愈成功率里消失")
        void shouldCountBudgetAbortedAttempt() {
            exhaustBudget();
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());

            HealingOutcome outcome = service.buildWithHealingOutcome(PROJECT_PATH, APP_ID);

            assertThat(outcome.getHealingAttempts()).isEqualTo(1);
        }
    }
}
