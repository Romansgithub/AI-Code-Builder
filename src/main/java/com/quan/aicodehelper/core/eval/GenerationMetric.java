package com.quan.aicodehelper.core.eval;

import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import lombok.Builder;
import lombok.Data;

/**
 * 单次生成的质量指标。
 *
 * <p>用 Builder 而非构造函数：字段较多，且埋点处只关心其中几项，
 * 具名参数能让调用代码自解释。</p>
 */
@Data
@Builder
public class GenerationMetric {

    /**
     * 关联应用 id。
     */
    private Long appId;

    /**
     * 评测批次标识（跑批时有值，单次生成时为 null）。
     */
    private String batchId;

    /**
     * 用户原始需求 / 评测用例 prompt。
     */
    private String userPrompt;

    /**
     * 代码生成类型。
     */
    private String codeGenType;

    /**
     * 首次构建是否直接通过（未经自愈）。
     *
     * <p>这是「一次通过率」的分子。对没有构建步骤的类型（HTML / 多文件），
     * 表示代码解析与保存是否成功。</p>
     */
    private Boolean firstBuildPassed;

    /**
     * 自愈修复轮数，0 表示未触发。
     */
    private Integer healingAttempts;

    /**
     * 最终是否通过。
     */
    private Boolean finalPassed;

    /**
     * 最终失败类型枚举名，通过时为 null。
     */
    private String failureType;

    /**
     * 失败原因摘要。
     */
    private String failureMessage;

    /**
     * 代码生成阶段耗时（毫秒）。
     */
    private Long generateCostMs;

    /**
     * 构建与自愈阶段耗时（毫秒）。
     */
    private Long buildCostMs;

    /**
     * 端到端总耗时（毫秒）。
     */
    private Long totalCostMs;

    /**
     * 产物文件数量。
     */
    private Integer fileCount;

    /**
     * 产物总字节数。
     */
    private Long totalBytes;

    /**
     * 用户 id。
     */
    private Long userId;

    /**
     * 用构建结果填充失败相关信息。
     *
     * @param failureType 失败类型枚举
     * @param failureMessage 失败原因
     */
    public GenerationMetric withFailure(BuildFailureTypeEnum failureType, String failureMessage) {
        this.failureType = failureType == null ? null : failureType.name();
        this.failureMessage = failureMessage;
        return this;
    }

    /**
     * 填充产物规模。
     */
    public GenerationMetric withOutput(String outputDir) {
        long[] size = CodeGenEvalRecorder.measureOutput(outputDir);
        this.fileCount = (int) size[0];
        this.totalBytes = size[1];
        return this;
    }
}
