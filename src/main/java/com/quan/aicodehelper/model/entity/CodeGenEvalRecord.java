package com.quan.aicodehelper.model.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 代码生成质量评测记录。独立成表（质量度量表）与 app 业务表分离：同一应用可反复生成评测，历史装不进 app 单字段。
 * 用途是把「AI 生成得怎么样」量化：一次通过率、自愈成功率、最终通过率、平均耗时、失败原因分布。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("code_gen_eval_record")
public class CodeGenEvalRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 关联应用 id（批量跑批的评测用例可能不落库为应用，此时为 null）
     */
    @Column("appId")
    private Long appId;

    /**
     * 评测批次标识。同一次跑批的记录共享该值，便于按批汇总；单次生成（非跑批）时为 null
     */
    @Column("batchId")
    private String batchId;

    @Column("userPrompt")
    private String userPrompt;

    /**
     * 代码生成类型（html / multi_file / vue_project）
     */
    @Column("codeGenType")
    private String codeGenType;

    /**
     * 首次构建是否直接通过（未经任何自愈修复）
     */
    @Column("firstBuildPassed")
    private Boolean firstBuildPassed;

    /**
     * 自愈修复轮数（0 表示未触发自愈）
     */
    @Column("healingAttempts")
    private Integer healingAttempts;

    /**
     * 最终是否通过构建 / 校验。HTML 与多文件模式无构建步骤，此处表示代码解析与保存是否成功
     */
    @Column("finalPassed")
    private Boolean finalPassed;

    /**
     * 最终失败类型（{@link com.quan.aicodehelper.model.enums.BuildFailureTypeEnum} 的 name）。通过时为 null
     */
    @Column("failureType")
    private String failureType;

    /**
     * 失败原因摘要（截断后的错误信息，便于统计 TOP N）
     */
    @Column("failureMessage")
    private String failureMessage;

    @Column("generateCostMs")
    private Long generateCostMs;

    @Column("buildCostMs")
    private Long buildCostMs;

    @Column("totalCostMs")
    private Long totalCostMs;

    @Column("fileCount")
    private Integer fileCount;

    @Column("totalBytes")
    private Long totalBytes;

    @Column("userId")
    private Long userId;

    @Column("createTime")
    private LocalDateTime createTime;

    @Column("updateTime")
    private LocalDateTime updateTime;

    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
