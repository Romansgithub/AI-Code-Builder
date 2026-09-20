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
    }

    /**
     * 跑批汇总指标：这就是最后写进报告、拿去面试讲的那几个数。
     */
    @Data
    public static class EvalSummary {
        /** 用例总数 */
        private int totalCount;
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
         * <p>分母是「实际执行的用例数」，不是用例集总数 ——
         * 中途异常没跑完的用例不该拉低这个比率。</p>
         */
        public double firstPassRate() {
            return totalCount == 0 ? 0.0 : round1(firstPassCount * 100.0 / totalCount);
        }

        /**
         * 最终通过率。
         */
        public double finalPassRate() {
            return totalCount == 0 ? 0.0 : round1(finalPassCount * 100.0 / totalCount);
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
        for (CaseResult c : cases) {
            if (c.isFirstBuildPassed()) {
                s.setFirstPassCount(s.getFirstPassCount() + 1);
            }
            if (c.isFinalPassed()) {
                s.setFinalPassCount(s.getFinalPassCount() + 1);
            }
            if (c.getHealingAttempts() > 0) {
                s.setHealingUsedCount(s.getHealingUsedCount() + 1);
                if (c.isFinalPassed()) {
                    s.setHealedSuccessCount(s.getHealedSuccessCount() + 1);
                }
            }
            generateSum += c.getGenerateCostMs();
            buildSum += c.getBuildCostMs();
        }
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
                summary.firstPassRate(), summary.getFirstPassCount(), summary.getTotalCount()));
        sb.append(String.format("最终通过率：%.1f%%（%d/%d）%n",
                summary.finalPassRate(), summary.getFinalPassCount(), summary.getTotalCount()));
        if (summary.getHealingUsedCount() > 0) {
            sb.append(String.format("自愈成功率：%.1f%%（%d/%d 条触发自愈）%n",
                    summary.healingSuccessRate(), summary.getHealedSuccessCount(),
                    summary.getHealingUsedCount()));
        } else {
            sb.append("自愈成功率：本次无用例触发自愈\n");
        }
        sb.append(String.format("平均生成耗时：%.1f 秒，平均构建耗时：%.1f 秒%n",
                summary.getAvgGenerateCostMs() / 1000.0, summary.getAvgBuildCostMs() / 1000.0));
        sb.append('\n');
        sb.append("── 逐条结果 ──\n");
        for (CaseResult c : cases) {
            sb.append(String.format("[%s] %s%n", c.isFinalPassed() ? "通过" : "失败", c.getCaseId()));
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
