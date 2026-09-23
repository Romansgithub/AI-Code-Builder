package com.quan.aicodehelper.model.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 评测报告视图对象。直接暴露 Mapper 返回的 {@code Map} 有两个问题：字段名拼错编译期发现不了，
 * 且前端拿不到「比率」这类派生值还得自己算；这里显式定义结构并把百分率算好。
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

    private Overall overall = new Overall();

    private List<ByType> byType = new ArrayList<>();

    private List<FailureItem> failures = new ArrayList<>();

    private List<HealingItem> healingAttempts = new ArrayList<>();

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
        /** 自愈成功率（%），healingUsedCount 为 0 时为 null，表示「无数据」而非「0%」 */
        private Double healingSuccessRate;
    }

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

    @Data
    public static class FailureItem {
        private String failureType;
        private String failureTypeText;
        private long failureCount;
        /** 占全部失败数的比例（%） */
        private double ratio;
    }

    @Data
    public static class HealingItem {
        private int healingAttempts;
        private long recordCount;
        private long passCount;
        /** 该轮数下最终通过的比例（%） */
        private double passRate;
    }
}
