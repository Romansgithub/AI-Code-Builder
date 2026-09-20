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
 * 代码生成质量评测记录。
 *
 * <p><b>为什么独立成表而不往 {@code app} 表加字段</b>：
 * {@code app} 是业务表（用户的应用），本表是质量度量表，两者关注点不同。
 * 同一个应用可以被反复生成与评测（用户改了 prompt 再生成一次就是一条新记录），
 * 塞进 {@code app} 的一个字段里装不下这些历史。</p>
 *
 * <p>本表的核心用途：把「AI 生成得怎么样」从主观感受变成可量化指标 ——
 * 一次通过率、自愈成功率、最终通过率、平均耗时、失败原因分布。</p>
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("code_gen_eval_record")
public class CodeGenEvalRecord implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 关联应用 id（批量跑批的评测用例可能不落库为应用，此时为 null）
     */
    @Column("appId")
    private Long appId;

    /**
     * 评测批次标识。同一次跑批的记录共享该值，便于按批汇总。
     * 单次生成（非跑批）时为 null。
     */
    @Column("batchId")
    private String batchId;

    /**
     * 用户原始需求 / 评测用例的 prompt
     */
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
     * 最终是否通过构建 / 校验。
     * 注意：HTML 与多文件模式没有构建步骤，此处表示代码解析与保存是否成功。
     */
    @Column("finalPassed")
    private Boolean finalPassed;

    /**
     * 最终失败类型（{@link com.quan.aicodehelper.model.enums.BuildFailureTypeEnum} 的 name）。
     * 通过时为 null。
     */
    @Column("failureType")
    private String failureType;

    /**
     * 失败原因摘要（截断后的错误信息，便于统计 TOP N）
     */
    @Column("failureMessage")
    private String failureMessage;

    /**
     * 代码生成阶段耗时（毫秒）
     */
    @Column("generateCostMs")
    private Long generateCostMs;

    /**
     * 构建与自愈阶段耗时（毫秒）
     */
    @Column("buildCostMs")
    private Long buildCostMs;

    /**
     * 端到端总耗时（毫秒）
     */
    @Column("totalCostMs")
    private Long totalCostMs;

    /**
     * 生成产物中的文件数量（可评估输出规模）
     */
    @Column("fileCount")
    private Integer fileCount;

    /**
     * 生成产物的总字节数
     */
    @Column("totalBytes")
    private Long totalBytes;

    /**
     * 创建用户id
     */
    @Column("userId")
    private Long userId;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;
}
