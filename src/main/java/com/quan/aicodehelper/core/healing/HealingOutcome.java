package com.quan.aicodehelper.core.healing;

import com.quan.aicodehelper.core.builder.BuildResult;
import lombok.Data;

/**
 * 自愈流程的完整结果。
 *
 * <p>相比裸的 {@link BuildResult}，这里额外携带「过程指标」：
 * 首次构建是否就通过、自愈用了几轮。这些是评测的关键数据，
 * 但把它们塞进 BuildResult 会污染「构建结果」本身的语义 —— 构建结果描述的是
 * 「最终构建成不成功」，而轮数描述的是「这个成功来得有多不容易」。</p>
 */
@Data
public class HealingOutcome {

    /**
     * 最终构建结果。
     */
    private BuildResult buildResult;

    /**
     * 首次构建是否直接通过（未经任何自愈修复）。
     */
    private boolean firstBuildPassed;

    /**
     * 自愈修复轮数，0 表示未触发。
     */
    private int healingAttempts;

    /**
     * 构建与自愈阶段耗时（毫秒）。
     */
    private long buildCostMs;

    public static HealingOutcome of(BuildResult buildResult, boolean firstBuildPassed,
                                    int healingAttempts, long buildCostMs) {
        HealingOutcome outcome = new HealingOutcome();
        outcome.setBuildResult(buildResult);
        outcome.setFirstBuildPassed(firstBuildPassed);
        outcome.setHealingAttempts(healingAttempts);
        outcome.setBuildCostMs(buildCostMs);
        return outcome;
    }

    public boolean isFinalPassed() {
        return buildResult != null && buildResult.isSuccess();
    }
}
