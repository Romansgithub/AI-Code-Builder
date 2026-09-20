package com.quan.aicodehelper.core.healing;

import com.quan.aicodehelper.ai.factory.AiCodeGeneratorServiceFactory;
import com.quan.aicodehelper.ai.service.AiCodeGeneratorService;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.config.SelfHealingProperties;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.builder.VueProjectBuilder;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 构建自愈循环测试。
 *
 * <p>构建动作本身（调 npm）不适合单测，因此这里 mock 掉 {@link VueProjectBuilder}
 * 与 AI 服务，专注验证<b>决策逻辑</b>：该不该重试、重试几次、什么时候停。</p>
 */
@DisplayName("构建自愈服务")
class BuildSelfHealingServiceTest {

    private static final Long APP_ID = 1001L;
    private static final String PROJECT_PATH = "/tmp/fake-project";

    private VueProjectBuilder vueProjectBuilder;
    private AiCodeGeneratorServiceFactory serviceFactory;
    private AiCodeGeneratorService aiService;
    private GenerationProgressRegistry progressRegistry;
    private BuildSelfHealingService service;

    /** 记录推送给前端的进度消息，便于断言用户能看到什么 */
    private final List<String> emittedMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        vueProjectBuilder = mock(VueProjectBuilder.class);
        serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        aiService = mock(AiCodeGeneratorService.class);
        progressRegistry = mock(GenerationProgressRegistry.class);

        when(serviceFactory.getAiCodeGeneratorService(anyLong(), any()))
                .thenReturn(aiService);
        // AI 调用返回一句简短说明即可，自愈逻辑不关心内容
        when(aiService.generateVueProjectCodeStream(anyLong(), anyString()))
                .thenReturn(Flux.just("已修复"));

        // 收集进度消息
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
    }

    private BuildResult compileError() {
        return BuildResult.fail(BuildFailureTypeEnum.COMPILE_ERROR, "项目构建",
                "error: Cannot find module './Missing.vue'", "raw output");
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
            verify(aiService, never()).generateVueProjectCodeStream(anyLong(), anyString());
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
            verify(aiService, never()).generateVueProjectCodeStream(anyLong(), anyString());
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

            verify(aiService, never()).generateVueProjectCodeStream(anyLong(), anyString());
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
            verify(aiService, times(1)).generateVueProjectCodeStream(anyLong(), anyString());
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
            verify(aiService, times(2)).generateVueProjectCodeStream(anyLong(), anyString());
        }

        @Test
        @DisplayName("用尽重试仍失败时，不应超过 maxAttempts 次 AI 调用")
        void shouldStopAtMaxAttempts() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isFalse();
            // maxAttempts = 2
            verify(aiService, times(2)).generateVueProjectCodeStream(anyLong(), anyString());
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
            verify(aiService, times(1)).generateVueProjectCodeStream(anyLong(), anyString());
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
    @DisplayName("AI 调用异常")
    class AiFailure {

        @Test
        @DisplayName("AI 调用抛异常时不应中断流程，应继续下一轮重试")
        void shouldContinueAfterAiException() {
            when(vueProjectBuilder.buildProjectWithResult(eq(PROJECT_PATH), eq(APP_ID)))
                    .thenReturn(compileError());
            when(aiService.generateVueProjectCodeStream(anyLong(), anyString()))
                    .thenThrow(new RuntimeException("模型服务不可用"));

            BuildResult result = service.buildWithHealing(PROJECT_PATH, APP_ID);

            assertThat(result.isSuccess()).isFalse();
            // 两次都抛异常，但流程没崩
            verify(aiService, times(2)).generateVueProjectCodeStream(anyLong(), anyString());
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

            verify(aiService, never()).generateVueProjectCodeStream(anyLong(), anyString());
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

            verify(aiService, never()).generateVueProjectCodeStream(anyLong(), anyString());
        }
    }
}
