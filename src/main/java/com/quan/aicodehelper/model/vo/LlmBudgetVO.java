package com.quan.aicodehelper.model.vo;

import lombok.Data;

/**
 * LLM 调用预算用量视图对象。
 *
 * <p>把「还有多少额度」做成可查询的接口，是因为额度耗尽时排障最需要的就是
 * 这个数字。放在日志里翻不方便，而且日志会轮转。</p>
 */
@Data
public class LlmBudgetVO {

    /**
     * 预算功能是否启用。false 表示调用不受次数限制。
     */
    private boolean enabled;

    /**
     * 配置的调用次数上限。
     */
    private int maxCalls;

    /**
     * 已消耗次数。
     */
    private long usedCalls;

    /**
     * 剩余次数（不会为负）。
     */
    private long remainingCalls;

    /**
     * 因预算耗尽被拦下的次数。与 usedCalls 分开：前者是「用完了」，
     * 后者是「用完之后又尝试了几次」。
     */
    private long rejectedCalls;

    /**
     * 已用百分比，便于前端直接展示进度条而不必自己算。
     */
    private int usedPercent;

    /**
     * 是否已触顶。
     */
    private boolean exhausted;

    /**
     * 触顶后是否会拒绝调用。false 表示只告警不拦截。
     */
    private boolean rejectWhenExhausted;

    /**
     * 人可读的一行摘要。
     */
    private String summary;
}
