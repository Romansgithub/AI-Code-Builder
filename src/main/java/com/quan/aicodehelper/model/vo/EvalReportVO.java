package com.quan.aicodehelper.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测报告视图对象。
 *
 * <p>直接暴露 Mapper 返回的 {@code Map} 会带来两个问题：字段名拼错在编译期发现不了，
 * 且前端拿不到「比率」这类派生值，还得自己算。这里显式定义结构，
 * 顺带把百分率算好。</p>
 */
@Data
public class EvalReportVO {

    /**
     * 统计口径说明，避免看报告的人误读数字。
     */
    private String scope;

    /**
     * 统计起始时间，null 表示全量。
     */
    private String since;

    /**
     * 批次标识，null 表示跨批次汇总。
     */
    private String batchId;

    /**
     * 总体指标。
     */
    private Overall overall = new Overall();

    /**
     * 分生成类型指标。
     */
    private List<ByType> byType = new ArrayList<>();

    /**
     * 失败原因分布。
     */
    private List<FailureItem> failures = new ArrayList<>();

    /**
     * 自愈轮数分布。
     */
    private List<HealingItem> healingAttempts = new ArrayList<>();

    /**
     * 总体指标。
     */
    @Data
    public static class Overall {
        private long totalCount;
        private long firstPassCount;
        private long finalPassCount;
        private long healingUsedCount;
        private long healedSuccessCount;
        private long avgTotalCostMs;
        /** 一次通过率（%） */
        private double firstPassRate;
        /** 最终通过率（%） */
        private double finalPassRate;
        /** 自愈成功率（%），healingUsedCount 为 0 时为 null —— 表示「无数据」而非「0%」 */
        private Double healingSuccessRate;
    }

    /**
     * 分类型指标。
     */
    @Data
    public static class ByType {
        private String codeGenType;
        /** 生成类型的中文名，前端不必自己维护映射 */
        private String codeGenTypeText;
        private long totalCount;
        private long firstPassCount;
        private long finalPassCount;
        private long healingUsedCount;
        private long healedSuccessCount;
        private long avgTotalCostMs;
        private double firstPassRate;
        private double finalPassRate;
        private Double healingSuccessRate;
    }

    /**
     * 失败原因分布项。
     */
    @Data
    public static class FailureItem {
        private String failureType;
        /** 失败类型的中文描述 */
        private String failureTypeText;
        private long failureCount;
        /** 占全部失败数的比例（%） */
        private double ratio;
    }

    /**
     * 自愈轮数分布项。
     */
    @Data
    public static class HealingItem {
        private int healingAttempts;
        private long recordCount;
        private long passCount;
        /** 该轮数下最终通过的比例（%） */
        private double passRate;
    }
}
