package com.quan.aicodehelper.core.builder;

import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import lombok.Data;

/**
 * Vue 项目构建结果。
 *
 * <p>为什么不再是 {@code boolean}：早期构建只返回成败，失败时错误日志仅打成控制台日志，
 * 调用方拿不到任何可用于修复的信息。自愈循环必须拿到「失败在哪个阶段」和「具体报错文本」，
 * 才能判断这次失败该不该重试、以及把什么内容喂回给模型。</p>
 */
@Data
public class BuildResult {

    /**
     * 构建是否成功。
     */
    private boolean success;

    /**
     * 失败类型。成功时为 {@link BuildFailureTypeEnum#NONE}。
     */
    private BuildFailureTypeEnum failureType;

    /**
     * 失败阶段的可读描述（如 "依赖安装" / "项目构建"）。
     */
    private String stage;

    /**
     * 提取后的错误信息，可直接拼进提示词。
     */
    private String errorMessage;

    /**
     * 原始输出（含 stdout/stderr），保留用于排查。
     */
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

    /**
     * 该失败是否可能通过「改代码」修复。
     *
     * <p>只有代码层面的问题才值得让模型重试；环境问题（依赖装不上、目录不存在）
     * 重试多少次都是一样的结果，纯粹浪费 token 和用户时间。</p>
     */
    public boolean isHealable() {
        return !success && failureType != null && failureType.isHealable();
    }
}
