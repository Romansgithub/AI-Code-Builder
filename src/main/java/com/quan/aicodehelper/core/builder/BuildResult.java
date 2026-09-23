package com.quan.aicodehelper.core.builder;

import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import lombok.Data;

/**
 * Vue 项目构建结果。自愈循环需要知道失败在哪个阶段、报错文本是什么，
 * 才判断得出该不该重试、把什么喂回模型，故不用 boolean。
 */
@Data
public class BuildResult {

    private boolean success;

    /** 成功时为 {@link BuildFailureTypeEnum#NONE}。 */
    private BuildFailureTypeEnum failureType;

    private String stage;

    private String errorMessage;

    private String rawOutput;

    public static BuildResult ok(String rawOutput) {
        BuildResult r = new BuildResult();
        r.setSuccess(true);
        r.setFailureType(BuildFailureTypeEnum.NONE);
        r.setRawOutput(rawOutput);
        return r;
    }

    public static BuildResult fail(BuildFailureTypeEnum type, String stage, String errorMessage, String rawOutput) {
        BuildResult r = new BuildResult();
        r.setSuccess(false);
        r.setFailureType(type);
        r.setStage(stage);
        r.setErrorMessage(errorMessage);
        r.setRawOutput(rawOutput);
        return r;
    }

    /** 只有「改代码能修」的失败才值得重试；环境问题（依赖装不上、目录不存在）重试结果相同。 */
    public boolean isHealable() {
        return !success && failureType != null && failureType.isHealable();
    }
}
