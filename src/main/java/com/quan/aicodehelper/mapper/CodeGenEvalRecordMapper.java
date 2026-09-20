package com.quan.aicodehelper.mapper;

import com.mybatisflex.core.BaseMapper;
import com.quan.aicodehelper.model.entity.CodeGenEvalRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 代码生成评测记录 映射层。
 *
 * <p>汇总类查询直接用 SQL 聚合，而不是把明细全捞进内存再算 ——
 * 评测记录会随每次生成持续累积，全量加载不现实。</p>
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
public interface CodeGenEvalRecordMapper extends BaseMapper<CodeGenEvalRecord> {

    /**
     * 按生成类型汇总指标。
     *
     * @param since   起始时间（含），可为 null 表示不限
     * @param batchId 批次标识，可为 null 表示全部批次
     * @return 每行包含 codeGenType、totalCount、firstPassCount、finalPassCount、healingCount
     */
    @Select("""
            <script>
            SELECT codeGenType                                            AS codeGenType,
                   COUNT(*)                                               AS totalCount,
                   SUM(CASE WHEN firstBuildPassed = 1 THEN 1 ELSE 0 END)  AS firstPassCount,
                   SUM(CASE WHEN finalPassed = 1 THEN 1 ELSE 0 END)       AS finalPassCount,
                   SUM(CASE WHEN healingAttempts &gt; 0 THEN 1 ELSE 0 END) AS healingUsedCount,
                   SUM(CASE WHEN finalPassed = 1 AND healingAttempts &gt; 0 THEN 1 ELSE 0 END) AS healedSuccessCount,
                   AVG(totalCostMs)                                       AS avgTotalCostMs
            FROM code_gen_eval_record
            WHERE isDelete = 0
            <if test="since != null"> AND createTime &gt;= #{since} </if>
            <if test="batchId != null and batchId != ''"> AND batchId = #{batchId} </if>
            GROUP BY codeGenType
            </script>
            """)
    List<Map<String, Object>> summaryByCodeGenType(@Param("since") LocalDateTime since,
                                                  @Param("batchId") String batchId);

    /**
     * 汇总总体指标（不分类型）。
     */
    @Select("""
            <script>
            SELECT COUNT(*)                                               AS totalCount,
                   SUM(CASE WHEN firstBuildPassed = 1 THEN 1 ELSE 0 END)  AS firstPassCount,
                   SUM(CASE WHEN finalPassed = 1 THEN 1 ELSE 0 END)       AS finalPassCount,
                   SUM(CASE WHEN healingAttempts &gt; 0 THEN 1 ELSE 0 END) AS healingUsedCount,
                   SUM(CASE WHEN finalPassed = 1 AND healingAttempts &gt; 0 THEN 1 ELSE 0 END) AS healedSuccessCount,
                   AVG(totalCostMs)                                       AS avgTotalCostMs
            FROM code_gen_eval_record
            WHERE isDelete = 0
            <if test="since != null"> AND createTime &gt;= #{since} </if>
            <if test="batchId != null and batchId != ''"> AND batchId = #{batchId} </if>
            </script>
            """)
    Map<String, Object> summaryOverall(@Param("since") LocalDateTime since,
                                      @Param("batchId") String batchId);

    /**
     * 失败原因分布（按失败类型聚合）。
     *
     * @param limit 最多返回多少条
     */
    @Select("""
            <script>
            SELECT failureType   AS failureType,
                   COUNT(*)      AS failureCount
            FROM code_gen_eval_record
            WHERE isDelete = 0 AND finalPassed = 0 AND failureType IS NOT NULL
            <if test="since != null"> AND createTime &gt;= #{since} </if>
            <if test="batchId != null and batchId != ''"> AND batchId = #{batchId} </if>
            GROUP BY failureType
            ORDER BY failureCount DESC
            LIMIT #{limit}
            </script>
            """)
    List<Map<String, Object>> failureDistribution(@Param("since") LocalDateTime since,
                                                 @Param("batchId") String batchId,
                                                 @Param("limit") int limit);

    /**
     * 自愈轮数分布 —— 可用于回答「修复一般几轮能成功」。
     */
    @Select("""
            <script>
            SELECT healingAttempts AS healingAttempts,
                   COUNT(*)        AS recordCount,
                   SUM(CASE WHEN finalPassed = 1 THEN 1 ELSE 0 END) AS passCount
            FROM code_gen_eval_record
            WHERE isDelete = 0
            <if test="since != null"> AND createTime &gt;= #{since} </if>
            <if test="batchId != null and batchId != ''"> AND batchId = #{batchId} </if>
            GROUP BY healingAttempts
            ORDER BY healingAttempts
            </script>
            """)
    List<Map<String, Object>> healingAttemptDistribution(@Param("since") LocalDateTime since,
                                                        @Param("batchId") String batchId);
}
