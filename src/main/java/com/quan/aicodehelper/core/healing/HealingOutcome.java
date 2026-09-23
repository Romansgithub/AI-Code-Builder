package com.quan.aicodehelper.core.healing;

import com.quan.aicodehelper.core.builder.BuildResult;
import lombok.Data;

/**
 * 自愈流程的完整结果，相比 {@link BuildResult} 额外携带评测所需的「过程指标」。
 */
@Data
public class HealingOutcome {

    private BuildResult buildResult;

    private boolean firstBuildPassed;

    /** 0 表示未触发自愈。 */
    private int healingAttempts;

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
