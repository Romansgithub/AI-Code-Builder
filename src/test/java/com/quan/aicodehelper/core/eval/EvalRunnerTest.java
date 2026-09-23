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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跑批执行器测试：聚焦「出错时会不会把整轮拖垮」与开关校验；汇总逻辑由 {@link EvalRunResultTest} 覆盖。
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

            // 没有挂死在用例上，在超时附近就返回了
            assertThat(cost).isLessThan(15_000L);
            assertThat(result.getCases()).hasSize(1);
            EvalRunResult.CaseResult cr = result.getCases().get(0);
            assertThat(cr.isTimedOut()).isTrue();
            assertThat(cr.isFinalPassed()).isFalse();
            assertThat(cr.getFailureType()).isEqualTo(BuildFailureTypeEnum.TIMEOUT.name());
        }
    }

    @Nested
    @DisplayName("单用例开关")
    class CaseFilter {

        /** 造一个含两条 HTML 用例的评测集 */
        private com.quan.aicodehelper.model.dto.eval.EvalSet twoCases() {
            com.quan.aicodehelper.model.dto.eval.EvalSet set =
                    new com.quan.aicodehelper.model.dto.eval.EvalSet();
            set.setName("t");
            set.setVersion("1.0");
            set.setCases(java.util.List.of(
                    htmlCase("vue-healing-trigger"), htmlCase("html-landing")));
            return set;
        }

        private com.quan.aicodehelper.model.dto.eval.EvalCase htmlCase(String id) {
            com.quan.aicodehelper.model.dto.eval.EvalCase c =
                    new com.quan.aicodehelper.model.dto.eval.EvalCase();
            c.setId(id);
            c.setCodeGenType(com.quan.aicodehelper.model.enums.CodeGenTypeEnum.HTML);
            c.setPrompt("p");
            return c;
        }

        @Test
        @DisplayName("指定 caseId 时只跑那一条，其余用例不该被调用")
        void shouldRunOnlySpecifiedCase() {
            props.setEnabled(true);
            props.setCaseTimeoutSeconds(1);
            props.init();
            when(loader.load()).thenReturn(twoCases());
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenReturn(Flux.never());

            EvalRunResult result = runner.run("vue-healing-trigger");

            assertThat(result.getCases()).hasSize(1);
            assertThat(result.getCases().get(0).getCaseId()).isEqualTo("vue-healing-trigger");
            // 另一条用例一次都不该被跑到，token 就省在这里
            verify(facade, times(1)).generateCodeStream(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("caseId 拼错时必须报错并列出可用 id，绝不能静默跑 0 条")
        void shouldFailLoudlyOnUnknownCaseId() {
            props.setEnabled(true);
            props.init();
            when(loader.load()).thenReturn(twoCases());

            assertThatThrownBy(() -> runner.run("vue-healing-triggr"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("vue-healing-triggr")
                    .hasMessageContaining("vue-healing-trigger")
                    .hasMessageContaining("html-landing");

            // 拼错时连一条都不该跑：宁可不跑，也不要给一份空报告当成功
            verify(facade, times(0)).generateCodeStream(anyString(), any(), anyLong());
        }

        @Test
        @DisplayName("不传 caseId 时仍跑全量，保持原有行为")
        void shouldRunAllWhenNoFilter() {
            props.setEnabled(true);
            props.setCaseTimeoutSeconds(1);
            props.init();
            when(loader.load()).thenReturn(twoCases());
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenReturn(Flux.never());

            EvalRunResult result = runner.run();

            assertThat(result.getCases()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("注入已应用却首轮通过")
    class InjectionWithoutEffect {

        @Test
        @DisplayName("应判本轮数据无意义，而不是上报一条看着正常的「一次通过」")
        void shouldFailWhenInjectionHasNoEffect() throws Exception {
            props.setEnabled(true);
            props.setCaseTimeoutSeconds(5);
            props.init();

            // 沙箱化产物根，避免往项目真实的 tmp/code_output 里写东西
            java.nio.file.Path sandbox = java.nio.file.Files.createTempDirectory("eval-guard-test");
            String originalRoot = com.quan.aicodehelper.constant.AppConstant.getCodeOutputRootDir();
            com.quan.aicodehelper.constant.AppConstant.Storage.apply(sandbox.toString(),
                    com.quan.aicodehelper.constant.AppConstant.getCodeDeployRootDir(),
                    com.quan.aicodehelper.constant.AppConstant.getCodeDeployHost());
            try {
                com.quan.aicodehelper.model.dto.eval.EvalSet set =
                        new com.quan.aicodehelper.model.dto.eval.EvalSet();
                set.setName("t");
                set.setVersion("1.0");
                com.quan.aicodehelper.model.dto.eval.EvalCase c =
                        new com.quan.aicodehelper.model.dto.eval.EvalCase();
                c.setId("vue-healing-trigger");
                c.setCodeGenType(com.quan.aicodehelper.model.enums.CodeGenTypeEnum.VUE_PROJECT);
                c.setPrompt("p");
                com.quan.aicodehelper.model.dto.eval.EvalCase.FaultInjection fi =
                        new com.quan.aicodehelper.model.dto.eval.EvalCase.FaultInjection();
                fi.setFind("'@/components/");
                fi.setReplace("'@/components-broken/");
                c.setFaultInjection(fi);
                set.setCases(java.util.List.of(c));
                when(loader.load()).thenReturn(set);

                // 生成阶段把带目标串的源文件写进产物目录，注入才有落点
                when(facade.generateCodeStream(anyString(), any(), anyLong())).thenAnswer(inv -> {
                    Long appId = inv.getArgument(2);
                    java.nio.file.Path src = java.nio.file.Paths.get(
                            com.quan.aicodehelper.constant.AppConstant.getCodeOutputRootDir(),
                            "vue_project_" + appId, "src", "pages");
                    java.nio.file.Files.createDirectories(src);
                    java.nio.file.Files.writeString(src.resolve("Home.vue"),
                            "<script setup>\nimport X from '@/components/X.vue'\n</script>\n");
                    return Flux.empty();
                });
                // 首轮就报「通过」：模拟产物在注入之后又被别的流程改写回原样
                com.quan.aicodehelper.core.healing.BuildSelfHealingService healing =
                        (com.quan.aicodehelper.core.healing.BuildSelfHealingService)
                                ReflectionTestUtils.getField(runner, "buildSelfHealingService");
                when(healing.buildWithHealingOutcome(anyString(), anyLong())).thenReturn(
                        com.quan.aicodehelper.core.healing.HealingOutcome.of(
                                com.quan.aicodehelper.core.builder.BuildResult.ok("ok"), true, 0, 10L));

                EvalRunResult result = runner.run();

                EvalRunResult.CaseResult cr = result.getCases().get(0);
                assertThat(cr.isFirstBuildPassed()).isFalse();
                assertThat(cr.isFinalPassed()).isFalse();
                assertThat(cr.getFailureMessage())
                        .contains("注入")
                        .contains("没有产生预期的构建失败");
            } finally {
                com.quan.aicodehelper.constant.AppConstant.Storage.apply(originalRoot,
                        com.quan.aicodehelper.constant.AppConstant.getCodeDeployRootDir(),
                        com.quan.aicodehelper.constant.AppConstant.getCodeDeployHost());
                cn.hutool.core.io.FileUtil.del(sandbox.toFile());
            }
        }
    }
}
