package com.quan.aicodehelper.core.eval;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮跑批的结果汇总。
 */
@Slf4j
@Data
public class EvalRunResult {

    /**
     * 批次标识，与落库的 batchId 一致。
     */
    private String batchId;

    private String evalSetName;

    private String evalSetVersion;

    private long startedAt;

    private long totalCostMs;

    private List<CaseResult> cases = new ArrayList<>();

    /**
     * 汇总统计。
     */
    private EvalSummary summary = new EvalSummary();

    /**
     * 单条用例的执行结果。
     */
    @Data
    public static class CaseResult {
        private String caseId;
        private String description;
        private String codeGenType;
        /** 首轮是否直接通过 */
        private boolean firstBuildPassed;
        /** 自愈轮数 */
        private int healingAttempts;
        /** 最终是否通过 */
        private boolean finalPassed;
        private String failureType;
        private String failureMessage;
        private long generateCostMs;
        private long buildCostMs;
        private long totalCostMs;
        private Integer fileCount;
        private Long totalBytes;
        /** 用例自身耗尽了超时时间 */
        private boolean timedOut;
        /** 执行过程中未预期的异常（如 AI 调用失败） */
        private String unexpectedError;
        /**
         * 该用例是否配了故障注入。
         *
         * <p>注入用例的首轮失败是<b>设计目的</b>（它靠注入制造必然失败来给自愈出题），
         * 因此不能计入一次通过率 —— 否则等于用一次刻意的失败去拉低 headline 指标，
         * 让报告读起来像「生成质量变差了」。它只该进自愈成功率的分子分母。</p>
         */
        private boolean faultInjected;
    }

    /**
     * 跑批汇总指标：这就是最后写进报告、拿去面试讲的那几个数。
     */
    @Data
    public static class EvalSummary {
        /** 实际执行的用例总数（含故障注入用例） */
        private int totalCount;
        /** 参与通过率计算的用例数 = totalCount - faultInjectionCount */
        private int ratedCount;
        /** 故障注入用例数（首轮必失败，不计入通过率） */
        private int faultInjectionCount;
        /** 首轮直接通过数 */
        private int firstPassCount;
        /** 最终通过数 */
        private int finalPassCount;
        /** 触发过自愈的用例数 */
        private int healingUsedCount;
        /** 自愈后成功数 */
        private int healedSuccessCount;
        /** 平均生成耗时 */
        private long avgGenerateCostMs;
        /** 平均构建耗时 */
        private long avgBuildCostMs;

        /**
         * 一次通过率（0-100，保留一位小数）。
         *
         * <p>分母是 {@link #ratedCount} 而不是用例总数：故障注入用例首轮必然失败
         * （那是它的设计目的），把它算进来会让「生成质量」这个指标凭空掉几个点，
         * 而掉的那部分恰恰是我们自己刻意制造的。</p>
         */
        public double firstPassRate() {
            return ratedCount == 0 ? 0.0 : round1(firstPassCount * 100.0 / ratedCount);
        }

        /**
         * 最终通过率。分母口径同 {@link #firstPassRate()}。
         */
        public double finalPassRate() {
            return ratedCount == 0 ? 0.0 : round1(finalPassCount * 100.0 / ratedCount);
        }

        /**
         * 自愈成功率 = 自愈后成功数 / 触发自愈数。
         *
         * <p>这是最能说明「自愈到底有没有用」的指标：没触发自愈时返回 0，
         * 但调用方应看 healingUsedCount 是否为 0，避免把「没机会自愈」
         * 误读成「自愈全失败」。</p>
         */
        public double healingSuccessRate() {
            return healingUsedCount == 0 ? 0.0 : round1(healedSuccessCount * 100.0 / healingUsedCount);
        }

        private static double round1(double v) {
            return Math.round(v * 10.0) / 10.0;
        }
    }

    /**
     * 由用例结果计算汇总。
     *
     * <p>用静态工厂而不是构造器：汇总逻辑要能被单独测试，
     * 不该和跑批流程（要调 AI、跑 npm）耦合在一起。</p>
     */
    public static EvalSummary summarize(List<CaseResult> cases) {
        EvalSummary s = new EvalSummary();
        if (cases == null || cases.isEmpty()) {
            return s;
        }
        s.setTotalCount(cases.size());
        long generateSum = 0;
        long buildSum = 0;
        int rated = 0;
        for (CaseResult c : cases) {
            if (c.isFaultInjected()) {
                // 注入用例不进通过率：它首轮必失败是设计使然，拿它算通过率
                // 就是让「我们自己制造的失败」去污染产品指标。
                s.setFaultInjectionCount(s.getFaultInjectionCount() + 1);
            } else {
                rated++;
                if (c.isFirstBuildPassed()) {
                    s.setFirstPassCount(s.getFirstPassCount() + 1);
                }
                if (c.isFinalPassed()) {
                    s.setFinalPassCount(s.getFinalPassCount() + 1);
                }
            }
            // 自愈成功率的分子分母照常包含注入用例 —— 它正是自愈的唯一度量样本。
            // 注意这里刻意**不要求**「自愈成功」才算数：被中止、预算耗尽而没有真跑起来的
            // 轮次也计入分母。否则最差的那批样本会从分子分母里双双消失，
            // 报告显示「本次无用例触发自愈」，而实情是自愈被触发了、一个字都没改。
            // 宁可比率难看，也不要让失败样本静默消失。
            if (c.getHealingAttempts() > 0) {
                s.setHealingUsedCount(s.getHealingUsedCount() + 1);
                if (c.isFinalPassed()) {
                    s.setHealedSuccessCount(s.getHealedSuccessCount() + 1);
                }
            }
            generateSum += c.getGenerateCostMs();
            buildSum += c.getBuildCostMs();
        }
        s.setRatedCount(rated);
        // 耗时平均仍按全部用例算：注入用例也是真跑过的，它的开销该计入成本
        s.setAvgGenerateCostMs(generateSum / cases.size());
        s.setAvgBuildCostMs(buildSum / cases.size());
        return s;
    }

    /**
     * 生成人类可读的跑批报告文本。
     */
    public String toReadableReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("评测集：").append(evalSetName).append(" v").append(evalSetVersion).append('\n');
        sb.append("批次：").append(batchId).append('\n');
        sb.append(String.format("用例：%d 条，总耗时 %.1f 秒%n", summary.getTotalCount(), totalCostMs / 1000.0));
        sb.append('\n');
        sb.append("── 汇总指标 ──\n");
        sb.append(String.format("一次通过率：%.1f%%（%d/%d）%n",
                summary.firstPassRate(), summary.getFirstPassCount(), summary.getRatedCount()));
        sb.append(String.format("最终通过率：%.1f%%（%d/%d）%n",
                summary.finalPassRate(), summary.getFinalPassCount(), summary.getRatedCount()));
        if (summary.getHealingUsedCount() > 0) {
            sb.append(String.format("自愈成功率：%.1f%%（%d/%d 条触发自愈）%n",
                    summary.healingSuccessRate(), summary.getHealedSuccessCount(),
                    summary.getHealingUsedCount()));
        } else {
            sb.append("自愈成功率：本次无用例触发自愈\n");
        }
        sb.append(String.format("平均生成耗时：%.1f 秒，平均构建耗时：%.1f 秒%n",
                summary.getAvgGenerateCostMs() / 1000.0, summary.getAvgBuildCostMs() / 1000.0));
        if (summary.getFaultInjectionCount() > 0) {
            sb.append(String.format(
                    "（通过率的分母是 %d 条而非总数的 %d 条：另 %d 条为故障注入用例，"
                            + "首轮必失败是它存在的目的，只用于验证自愈）%n",
                    summary.getRatedCount(), summary.getTotalCount(),
                    summary.getFaultInjectionCount()));
            if (summary.getHealingUsedCount() == 0) {
                // 这一行是把「空数据」和「0% 成功率」区分开的关键。
                // 曾经出现过：注入的 find 串写错导致一次都没命中，自愈从未被触发，
                // 报告却只显示「自愈成功率：本次无用例触发自愈」——
                // 看起来一切正常，实际上整条链路根本没被验证过。
                sb.append("⚠ 注意：配置了故障注入用例，却没有任何用例触发自愈。"
                        + "说明注入没生效或自愈链路没跑起来，本轮自愈数据不可用，"
                        + "请检查上面注入用例的失败原因。\n");
            }
        }
        sb.append('\n');
        sb.append("── 逐条结果 ──\n");
        for (CaseResult c : cases) {
            sb.append(String.format("[%s] %s%s%n", c.isFinalPassed() ? "通过" : "失败", c.getCaseId(),
                    c.isFaultInjected() ? "（故障注入，不计入通过率）" : ""));
            sb.append(String.format("  类型=%s 首轮=%s 自愈=%d轮 生成=%.1fs 构建=%.1fs",
                    c.getCodeGenType(), c.isFirstBuildPassed() ? "通过" : "失败",
                    c.getHealingAttempts(), c.getGenerateCostMs() / 1000.0, c.getBuildCostMs() / 1000.0));
            if (c.getFileCount() != null) {
                sb.append(String.format(" 产物=%d个文件/%.1fKB", c.getFileCount(),
                        c.getTotalBytes() == null ? 0.0 : c.getTotalBytes() / 1024.0));
            }
            sb.append('\n');
            if (!c.isFinalPassed()) {
                sb.append("  失败类型：").append(c.getFailureType()).append('\n');
                if (c.getFailureMessage() != null) {
                    String msg = c.getFailureMessage().length() > 200
                            ? c.getFailureMessage().substring(0, 200) + "..."
                            : c.getFailureMessage();
                    sb.append("  失败原因：").append(msg.replace("\n", " ")).append('\n');
                }
            }
            if (c.getUnexpectedError() != null) {
                sb.append("  执行异常：").append(c.getUnexpectedError()).append('\n');
            }
        }
        return sb.toString();
    }
}
