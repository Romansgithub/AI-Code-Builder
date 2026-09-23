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

    private String batchId;

    private String evalSetName;

    private String evalSetVersion;

    private long startedAt;

    private long totalCostMs;

    private List<CaseResult> cases = new ArrayList<>();

    private EvalSummary summary = new EvalSummary();

    @Data
    public static class CaseResult {
        private String caseId;
        private String description;
        private String codeGenType;
        private boolean firstBuildPassed;
        private int healingAttempts;
        private boolean finalPassed;
        private String failureType;
        private String failureMessage;
        private long generateCostMs;
        private long buildCostMs;
        private long totalCostMs;
        private Integer fileCount;
        private Long totalBytes;
        private boolean timedOut;
        private String unexpectedError;
        /**
         * 该用例是否配了故障注入。注入用例首轮失败是设计目的（靠注入给自愈出题），
         * 不能计入一次通过率，只进自愈成功率的分子分母。
         */
        private boolean faultInjected;
    }

    /**
     * 跑批汇总指标。
     */
    @Data
    public static class EvalSummary {
        /** 实际执行的用例总数（含故障注入用例） */
        private int totalCount;
        /** 参与通过率计算的用例数 = totalCount - faultInjectionCount */
        private int ratedCount;
        /** 故障注入用例数（首轮必失败，不计入通过率） */
        private int faultInjectionCount;
        private int firstPassCount;
        private int finalPassCount;
        private int healingUsedCount;
        private int healedSuccessCount;
        private long avgGenerateCostMs;
        private long avgBuildCostMs;

        /**
         * 一次通过率（0-100，保留一位小数）。分母是 {@link #ratedCount} 而非用例总数：
         * 故障注入用例首轮必然失败（那是它的设计目的），算进来会让生成质量指标凭空掉几个点。
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
         * 自愈成功率 = 自愈后成功数 / 触发自愈数。没触发自愈时返回 0，
         * 调用方需看 healingUsedCount 是否为 0，避免把「没机会自愈」误读成「自愈全失败」。
         */
        public double healingSuccessRate() {
            return healingUsedCount == 0 ? 0.0 : round1(healedSuccessCount * 100.0 / healingUsedCount);
        }

        private static double round1(double v) {
            return Math.round(v * 10.0) / 10.0;
        }
    }

    /**
     * 由用例结果计算汇总。用静态工厂而非构造器：汇总逻辑要能被单独测试，
     * 不该和跑批流程（调 AI、跑 npm）耦合。
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
                // 注入用例不进通过率：它首轮必失败是设计使然，拿它算等于让刻意制造的失败污染产品指标。
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
            // 自愈成功率的分子分母照常含注入用例（它是自愈唯一的度量样本），且刻意不要求「自愈成功」
            // 才算数：被中止、预算耗尽的轮次也计入分母，否则最差样本会双双消失，报告显示「未触发自愈」。
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
                // 把「空数据」和「0% 成功率」区分开：注入没命中时自愈从未被触发，
                // 报告只显示「未触发自愈」看着正常，但整条链路根本没被验证过。
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
