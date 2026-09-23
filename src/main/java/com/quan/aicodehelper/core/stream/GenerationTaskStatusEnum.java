package com.quan.aicodehelper.core.stream;

import lombok.Getter;

/**
 * 一次代码生成任务的生命周期状态。前端需据此回答「生成还在跑吗」与「最后成功了吗」，
 * 后者无法靠事件流恢复（流结束连接就断），只能靠状态查询恢复现场。
 */
@Getter
public enum GenerationTaskStatusEnum {

    RUNNING("running", "生成中"),

    COMPLETED("completed", "已完成"),

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
