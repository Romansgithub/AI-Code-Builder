package com.quan.aicodehelper.model.vo;

import lombok.Data;

/**
 * LLM 调用预算用量视图对象。额度耗尽时排障最需要这个数字，而日志不方便翻且会轮转。
 */
@Data
public class LlmBudgetVO {

    /**
     * 预算功能是否启用。false 表示调用不受次数限制。
     */
    private boolean enabled;

    private int maxCalls;

    private long usedCalls;

    /**
     * 剩余次数，不会为负。
     */
    private long remainingCalls;

    /**
     * 因预算耗尽被拦下的次数。与 usedCalls 分开：后者是「用完了」，前者是「用完之后又尝试了几次」。
     */
    private long rejectedCalls;

    private int usedPercent;

    private boolean exhausted;

    /**
     * 触顶后是否会拒绝调用。false 表示只告警不拦截。
     */
    private boolean rejectWhenExhausted;

    private String summary;
}
