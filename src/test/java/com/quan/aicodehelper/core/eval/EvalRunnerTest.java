package com.quan.aicodehelper.core.eval;

import com.quan.aicodehelper.ai.facade.AiCodeGeneratorFacade;
import com.quan.aicodehelper.config.EvalProperties;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 跑批执行器测试。
 *
 * <p>真实跑批要调 AI 并跑 npm，无法在单测里跑完。因此这里聚焦两个
 * 「出错时会不会把整轮拖垮」的行为，以及开关校验 —— 跑批流程的正确性
 * 由 {@link EvalRunResultTest} 覆盖的汇总逻辑保证。</p>
 */
@DisplayName("评测跑批执行器")
class EvalRunnerTest {

    private EvalRunner runner;
    private EvalProperties props;
    private AiCodeGeneratorFacade facade;
    private EvalSetLoader loader;

    @BeforeEach
    void setUp() {
        runner = new EvalRunner();
        props = new EvalProperties();
        facade = mock(AiCodeGeneratorFacade.class);
        loader = mock(EvalSetLoader.class);

        props.setEnabled(false);
        props.setCaseTimeoutSeconds(2);
        props.setEvalSetPath("eval/codegen-eval-set.json");
        props.setOutputSubDir("eval-run");
        props.init();

        ReflectionTestUtils.setField(runner, "evalProperties", props);
        ReflectionTestUtils.setField(runner, "aiCodeGeneratorFacade", facade);
        ReflectionTestUtils.setField(runner, "evalSetLoader", loader);
        ReflectionTestUtils.setField(runner, "buildSelfHealingService",
                mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class));
        ReflectionTestUtils.setField(runner, "codeGenEvalRecorder",
                mock(CodeGenEvalRecorder.class));
    }

    @Nested
    @DisplayName("启用开关")
    class EnabledGuard {

        @Test
        @DisplayName("未启用时应直接拒绝，而不是静默跑一轮烧 token")
        void shouldRejectWhenDisabled() {
            props.setEnabled(false);

            assertThatThrownBy(() -> runner.run())
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未启用");
        }
    }

    @Nested
    @DisplayName("单条用例卡死")
    class CaseTimeout {

        @Test
        @DisplayName("用例卡住不动时应按超时记失败，而不是让整轮跑批无限等")
        void shouldMarkTimeoutAndContinue() {
            props.setEnabled(true);
            // 超时设 1 秒，方便测试
            props.setCaseTimeoutSeconds(1);
            props.init();

            com.quan.aicodehelper.model.dto.eval.EvalSet set =
                    new com.quan.aicodehelper.model.dto.eval.EvalSet();
            set.setName("t");
            set.setVersion("1.0");
            com.quan.aicodehelper.model.dto.eval.EvalCase c =
                    new com.quan.aicodehelper.model.dto.eval.EvalCase();
            c.setId("stuck-case");
            c.setCodeGenType(com.quan.aicodehelper.model.enums.CodeGenTypeEnum.HTML);
            c.setPrompt("p");
            set.setCases(java.util.List.of(c));
            when(loader.load()).thenReturn(set);

            // 永远不返回的流：模拟模型卡住
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenReturn(Flux.never());

            long startedAt = System.currentTimeMillis();
            EvalRunResult result = runner.run();
            long cost = System.currentTimeMillis() - startedAt;

            // 关键：没有挂死在用例上，在超时附近就返回了
            assertThat(cost).isLessThan(15_000L);
            assertThat(result.getCases()).hasSize(1);
            EvalRunResult.CaseResult cr = result.getCases().get(0);
            assertThat(cr.isTimedOut()).isTrue();
            assertThat(cr.isFinalPassed()).isFalse();
            assertThat(cr.getFailureType()).isEqualTo(BuildFailureTypeEnum.TIMEOUT.name());
        }
    }
}
