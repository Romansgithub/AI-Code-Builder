package com.quan.aicodehelper.core.eval;

import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import lombok.Builder;
import lombok.Data;

/**
 * 单次生成的质量指标。
 */
@Data
@Builder
public class GenerationMetric {

    private Long appId;

    /** 跑批时有值，单次生成时为 null */
    private String batchId;

    private String userPrompt;

    private String codeGenType;

    /**
     * 首次构建是否直接通过（未经自愈）。这是「一次通过率」的分子；
     * 对无构建步骤的类型（HTML / 多文件）表示解析与保存是否成功。
     */
    private Boolean firstBuildPassed;

    /** 自愈修复轮数，0 表示未触发 */
    private Integer healingAttempts;

    private Boolean finalPassed;

    /** 最终失败类型枚举名，通过时为 null */
    private String failureType;

    private String failureMessage;

    private Long generateCostMs;

    private Long buildCostMs;

    private Long totalCostMs;

    private Integer fileCount;

    private Long totalBytes;

    private Long userId;

    /**
     * 用构建结果填充失败相关信息。
     */
    public GenerationMetric withFailure(BuildFailureTypeEnum failureType, String failureMessage) {
        this.failureType = failureType == null ? null : failureType.name();
        this.failureMessage = failureMessage;
        return this;
    }

    public GenerationMetric withOutput(String outputDir) {
        long[] size = CodeGenEvalRecorder.measureOutput(outputDir);
        this.fileCount = (int) size[0];
        this.totalBytes = size[1];
        return this;
    }
}
