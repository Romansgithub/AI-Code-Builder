package com.quan.aicodehelper.core.stream;

import lombok.Getter;

/**
 * 一次代码生成任务的生命周期状态。
 *
 * <p><b>为什么需要显式状态</b>：前端要回答两个问题 ——
 * 「我刚才发起的生成还在跑吗」和「它最后成功了吗」。
 * 只靠事件流回答不了第二个：流结束后连接就断了，
 * 用户刷新页面时只能靠状态查询恢复现场。</p>
 */
@Getter
public enum GenerationTaskStatusEnum {

    /**
     * 已受理，生成线程已启动或即将启动。
     */
    RUNNING("running", "生成中"),

    /**
     * 生成流正常结束，代码已落盘。
     */
    COMPLETED("completed", "已完成"),

    /**
     * 生成失败（AI 报错、超时、保存失败等）。
     */
    FAILED("failed", "已失败");

    private final String value;
    private final String description;

    GenerationTaskStatusEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }

    public boolean isFinished() {
        return this != RUNNING;
    }

    public static GenerationTaskStatusEnum getEnumByValue(String value) {
        for (GenerationTaskStatusEnum e : values()) {
            if (e.value.equals(value)) {
                return e;
            }
        }
        return null;
    }
}
