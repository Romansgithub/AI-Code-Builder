package com.quan.aicodehelper.core.eval;

import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑批汇总计算测试。
 *
 * <p>这些数字最终会写进报告、拿去讲「自愈有多大用」。算错了没人会察觉，
 * 但结论就全反了 —— 所以这里的边界情况必须逐个锁死。</p>
 */
@DisplayName("评测汇总计算")
class EvalRunResultTest {

    private static EvalRunResult.CaseResult caseOf(boolean firstPassed, int attempts, boolean finalPassed) {
        EvalRunResult.CaseResult c = new EvalRunResult.CaseResult();
        c.setCaseId("case");
        c.setFirstBuildPassed(firstPassed);
        c.setHealingAttempts(attempts);
        c.setFinalPassed(finalPassed);
        c.setGenerateCostMs(1000L);
        c.setBuildCostMs(2000L);
        return c;
    }

    @Nested
    @DisplayName("空输入")
    class EmptyInput {

        @Test
        @DisplayName("空列表应返回全 0 而不是抛异常")
        void shouldHandleEmptyList() {
            EvalRunResult.EvalSummary s = EvalRunResult.summarize(Collections.emptyList());

            assertThat(s.getTotalCount()).isZero();
            assertThat(s.firstPassRate()).isZero();
            assertThat(s.finalPassRate()).isZero();
            assertThat(s.healingSuccessRate()).isZero();
        }

        @Test
        @DisplayName("null 入参应返回全 0")
        void shouldHandleNull() {
            EvalRunResult.EvalSummary s = EvalRunResult.summarize(null);

            assertThat(s.getTotalCount()).isZero();
        }
    }

    @Nested
    @DisplayName("一次通过率")
    class FirstPassRate {

        @Test
        @DisplayName("全部一次通过时应为 100%")
        void shouldBe100WhenAllFirstPass() {
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                cases.add(caseOf(true, 0, true));
            }

            EvalRunResult.EvalSummary s = EvalRunResult.summarize(cases);

            assertThat(s.getTotalCount()).isEqualTo(4);
            assertThat(s.getFirstPassCount()).isEqualTo(4);
            assertThat(s.firstPassRate()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("一半一次通过时应为 50%")
        void shouldBeHalfWhenHalfFirstPass() {
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            cases.add(caseOf(true, 0, true));
            cases.add(caseOf(false, 1, true));

            EvalRunResult.EvalSummary s = EvalRunResult.summarize(cases);

            assertThat(s.firstPassRate()).isEqualTo(50.0);
            // 最终都通过了，所以最终通过率是 100%
            assertThat(s.finalPassRate()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("比率应保留一位小数，不做整数截断")
        void shouldKeepOneDecimal() {
            // 1/3 = 33.333... → 33.3
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            cases.add(caseOf(true, 0, true));
            cases.add(caseOf(false, 0, false));
            cases.add(caseOf(false, 0, false));

            EvalRunResult.EvalSummary s = EvalRunResult.summarize(cases);

            assertThat(s.firstPassRate()).isEqualTo(33.3);
        }
    }

    @Nested
    @DisplayName("自愈成功率")
    class HealingSuccessRate {

        @Test
        @DisplayName("无用例触发自愈时成功率为 0，且 usedCount 为 0（调用方据此区分「没机会」与「全失败」）")
        void shouldBeZeroWhenNoHealingUsed() {
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            cases.add(caseOf(true, 0, true));
            cases.add(caseOf(true, 0, true));

            EvalRunResult.EvalSummary s = EvalRunResult.summarize(cases);

            assertThat(s.getHealingUsedCount()).isZero();
            assertThat(s.healingSuccessRate()).isZero();
        }

        @Test
        @DisplayName("自愈一次成功时应为 100%")
        void shouldBe100WhenHealingSucceeded() {
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            cases.add(caseOf(false, 1, true));

            EvalRunResult.EvalSummary s = EvalRunResult.summarize(cases);

            assertThat(s.getHealingUsedCount()).isEqualTo(1);
            assertThat(s.getHealedSuccessCount()).isEqualTo(1);
            assertThat(s.healingSuccessRate()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("自愈后仍失败时应为 0%")
        void shouldBeZeroWhenHealingFailed() {
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            cases.add(caseOf(false, 2, false));

            EvalRunResult.EvalSummary s = EvalRunResult.summarize(cases);

            assertThat(s.getHealingUsedCount()).isEqualTo(1);
            assertThat(s.getHealedSuccessCount()).isZero();
            assertThat(s.healingSuccessRate()).isZero();
        }

        @Test
        @DisplayName("自愈成功率的计算基数只含触发自愈的用例，不含一次通过的那些")
        void shouldOnlyCountHealingCasesAsDenominator() {
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            // 6 条一次通过（不触发自愈）
            for (int i = 0; i < 6; i++) {
                cases.add(caseOf(true, 0, true));
            }
            // 2 条触发自愈，1 条成功
            cases.add(caseOf(false, 1, true));
            cases.add(caseOf(false, 2, false));

            EvalRunResult.EvalSummary s = EvalRunResult.summarize(cases);

            // 分母必须是 2（触发自愈数）而不是 8（用例总数），否则 50% 会被稀释成 12.5%
            assertThat(s.getHealingUsedCount()).isEqualTo(2);
            assertThat(s.healingSuccessRate()).isEqualTo(50.0);
        }
    }

    @Nested
    @DisplayName("耗时统计")
    class CostStats {

        @Test
        @DisplayName("平均耗时应是各用例的算术平均")
        void shouldAverageCosts() {
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            EvalRunResult.CaseResult a = caseOf(true, 0, true);
            a.setGenerateCostMs(1000L);
            a.setBuildCostMs(3000L);
            EvalRunResult.CaseResult b = caseOf(true, 0, true);
            b.setGenerateCostMs(3000L);
            b.setBuildCostMs(1000L);
            cases.add(a);
            cases.add(b);

            EvalRunResult.EvalSummary s = EvalRunResult.summarize(cases);

            assertThat(s.getAvgGenerateCostMs()).isEqualTo(2000L);
            assertThat(s.getAvgBuildCostMs()).isEqualTo(2000L);
        }
    }

    @Nested
    @DisplayName("可读报告")
    class ReadableReport {

        @Test
        @DisplayName("报告应包含评测集版本与关键指标，便于直接贴到记录里")
        void shouldContainKeyMetrics() {
            EvalRunResult run = new EvalRunResult();
            run.setBatchId("eval-abc12345");
            run.setEvalSetName("代码生成基础回归集");
            run.setEvalSetVersion("1.0");
            run.setTotalCostMs(123_400L);
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            cases.add(caseOf(true, 0, true));
            cases.add(caseOf(false, 2, true));
            run.setCases(cases);
            run.setSummary(EvalRunResult.summarize(cases));

            String report = run.toReadableReport();

            assertThat(report).contains("代码生成基础回归集");
            assertThat(report).contains("v1.0");
            assertThat(report).contains("eval-abc12345");
            assertThat(report).contains("一次通过率");
            assertThat(report).contains("自愈成功率");
        }

        @Test
        @DisplayName("无用例触发自愈时应明说，而不是显示 0%")
        void shouldNotShowMisleadingZeroHealingRate() {
            EvalRunResult run = new EvalRunResult();
            run.setEvalSetName("集");
            run.setEvalSetVersion("1.0");
            run.setBatchId("b");
            List<EvalRunResult.CaseResult> cases = new ArrayList<>();
            cases.add(caseOf(true, 0, true));
            run.setCases(cases);
            run.setSummary(EvalRunResult.summarize(cases));

            String report = run.toReadableReport();

            // 0% 会被读成「自愈全失败」，必须区分开
            assertThat(report).contains("无用例触发自愈");
        }

        @Test
        @DisplayName("失败用例应在报告中给出失败类型与原因")
        void shouldShowFailureDetail() {
            EvalRunResult run = new EvalRunResult();
            run.setEvalSetName("集");
            run.setEvalSetVersion("1.0");
            run.setBatchId("b");
            EvalRunResult.CaseResult failed = caseOf(false, 2, false);
            failed.setCaseId("vue-todo");
            failed.setCodeGenType("vue_project");
            failed.setFailureType(BuildFailureTypeEnum.COMPILE_ERROR.name());
            failed.setFailureMessage("error: Cannot find module './Missing.vue'");
            run.setCases(List.of(failed));
            run.setSummary(EvalRunResult.summarize(List.of(failed)));

            String report = run.toReadableReport();

            assertThat(report).contains("vue-todo");
            assertThat(report).contains("COMPILE_ERROR");
            assertThat(report).contains("Missing.vue");
        }

        @Test
        @DisplayName("超长失败原因应被截断，避免报告被一条日志刷屏")
        void shouldTruncateLongFailureMessage() {
            EvalRunResult run = new EvalRunResult();
            run.setEvalSetName("集");
            run.setEvalSetVersion("1.0");
            run.setBatchId("b");
            EvalRunResult.CaseResult failed = caseOf(false, 1, false);
            failed.setCaseId("c");
            failed.setFailureType(BuildFailureTypeEnum.COMPILE_ERROR.name());
            failed.setFailureMessage("x".repeat(5000));
            run.setCases(List.of(failed));
            run.setSummary(EvalRunResult.summarize(List.of(failed)));

            String report = run.toReadableReport();

            assertThat(report).contains("...");
            // 5000 字符的原始信息不该原样进报告
            assertThat(report.length()).isLessThan(3000);
        }
    }
}
