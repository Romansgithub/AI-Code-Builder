package com.quan.aicodehelper.service.impl;

import com.quan.aicodehelper.mapper.CodeGenEvalRecordMapper;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.model.vo.EvalReportVO;
import com.mybatisflex.core.util.ConvertUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测报告服务。
 *
 * <p>把 SQL 聚合的原始行转成带比率的视图对象。比率统一在这里算：
 * SQL 里算百分比要处理除零，且不同数据库行为不一致（MySQL 返回 NULL、
 * H2 可能报错），放到 Java 里更可控、也更好测。</p>
 */
@Slf4j
@Service
public class EvalReportService {

    /**
     * 失败原因最多返回多少条，防止低频类型把报告刷屏。
     */
    private static final int FAILURE_LIMIT = 20;

    @Resource
    private CodeGenEvalRecordMapper evalRecordMapper;

    /**
     * 生成评测报告。
     *
     * @param sinceDays 统计最近多少天，null 或 &lt;=0 表示全量
     * @param batchId   批次标识，可为 null
     * @return 报告
     */
    public EvalReportVO buildReport(Integer sinceDays, String batchId) {
        LocalDateTime since = (sinceDays == null || sinceDays <= 0)
                ? null : LocalDateTime.now().minusDays(sinceDays);

        EvalReportVO vo = new EvalReportVO();
        vo.setSince(since == null ? null : since.toString());
        vo.setBatchId(batchId);
        vo.setScope(batchId == null
                ? (since == null ? "全部生成记录" : "最近 " + sinceDays + " 天全部生成记录")
                : ("批次 " + batchId));

        vo.setOverall(toOverall(evalRecordMapper.summaryOverall(since, batchId)));
        vo.setByType(toByTypeList(evalRecordMapper.summaryByCodeGenType(since, batchId)));
        vo.setFailures(toFailureList(evalRecordMapper.failureDistribution(since, batchId, FAILURE_LIMIT)));
        vo.setHealingAttempts(toHealingList(evalRecordMapper.healingAttemptDistribution(since, batchId)));
        return vo;
    }

    private EvalReportVO.Overall toOverall(Map<String, Object> row) {
        EvalReportVO.Overall o = new EvalReportVO.Overall();
        if (row == null) {
            return o;
        }
        o.setTotalCount(longOf(row.get("totalCount")));
        o.setFirstPassCount(longOf(row.get("firstPassCount")));
        o.setFinalPassCount(longOf(row.get("finalPassCount")));
        o.setHealingUsedCount(longOf(row.get("healingUsedCount")));
        o.setHealedSuccessCount(longOf(row.get("healedSuccessCount")));
        o.setAvgTotalCostMs(longOf(row.get("avgTotalCostMs")));
        o.setFirstPassRate(rate(o.getFirstPassCount(), o.getTotalCount()));
        o.setFinalPassRate(rate(o.getFinalPassCount(), o.getTotalCount()));
        o.setHealingSuccessRate(nullableRate(o.getHealedSuccessCount(), o.getHealingUsedCount()));
        return o;
    }

    private List<EvalReportVO.ByType> toByTypeList(List<Map<String, Object>> rows) {
        List<EvalReportVO.ByType> list = new ArrayList<>();
        if (rows == null) {
            return list;
        }
        for (Map<String, Object> row : rows) {
            EvalReportVO.ByType t = new EvalReportVO.ByType();
            String type = strOf(row.get("codeGenType"));
            t.setCodeGenType(type);
            t.setCodeGenTypeText(typeText(type));
            t.setTotalCount(longOf(row.get("totalCount")));
            t.setFirstPassCount(longOf(row.get("firstPassCount")));
            t.setFinalPassCount(longOf(row.get("finalPassCount")));
            t.setHealingUsedCount(longOf(row.get("healingUsedCount")));
            t.setHealedSuccessCount(longOf(row.get("healedSuccessCount")));
            t.setAvgTotalCostMs(longOf(row.get("avgTotalCostMs")));
            t.setFirstPassRate(rate(t.getFirstPassCount(), t.getTotalCount()));
            t.setFinalPassRate(rate(t.getFinalPassCount(), t.getTotalCount()));
            t.setHealingSuccessRate(nullableRate(t.getHealedSuccessCount(), t.getHealingUsedCount()));
            list.add(t);
        }
        return list;
    }

    private List<EvalReportVO.FailureItem> toFailureList(List<Map<String, Object>> rows) {
        List<EvalReportVO.FailureItem> list = new ArrayList<>();
        if (rows == null) {
            return list;
        }
        long totalFailures = 0;
        for (Map<String, Object> row : rows) {
            totalFailures += longOf(row.get("failureCount"));
        }
        for (Map<String, Object> row : rows) {
            EvalReportVO.FailureItem item = new EvalReportVO.FailureItem();
            String type = strOf(row.get("failureType"));
            item.setFailureType(type);
            item.setFailureTypeText(failureText(type));
            item.setFailureCount(longOf(row.get("failureCount")));
            item.setRatio(rate(item.getFailureCount(), totalFailures));
            list.add(item);
        }
        return list;
    }

    private List<EvalReportVO.HealingItem> toHealingList(List<Map<String, Object>> rows) {
        List<EvalReportVO.HealingItem> list = new ArrayList<>();
        if (rows == null) {
            return list;
        }
        for (Map<String, Object> row : rows) {
            EvalReportVO.HealingItem item = new EvalReportVO.HealingItem();
            item.setHealingAttempts((int) longOf(row.get("healingAttempts")));
            item.setRecordCount(longOf(row.get("recordCount")));
            item.setPassCount(longOf(row.get("passCount")));
            item.setPassRate(rate(item.getPassCount(), item.getRecordCount()));
            list.add(item);
        }
        return list;
    }

    /**
     * 百分率，保留一位小数。分母为 0 时返回 0。
     */
    static double rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    /**
     * 分母为 0 时返回 null，语义是「没有数据」而不是「0%」。
     *
     * <p>这个区分很重要：自愈成功率的 0% 和「从未触发过自愈」是两件事，
     * 混在一起会得出「自愈完全没用」的错误结论。</p>
     */
    static Double nullableRate(long numerator, long denominator) {
        return denominator <= 0 ? null : rate(numerator, denominator);
    }

    private static long longOf(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            // AVG 在 MySQL 返回 BigDecimal，某些驱动会带小数
            try {
                return (long) Double.parseDouble(v.toString().trim());
            } catch (NumberFormatException ex) {
                return 0L;
            }
        }
    }

    private static String strOf(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * 生成类型 value 转中文名，未知值原样返回便于发现新增类型未登记。
     */
    static String typeText(String value) {
        if (value == null) {
            return null;
        }
        CodeGenTypeEnum e = CodeGenTypeEnum.getEnumByValue(value);
        return e == null ? value : e.getText();
    }

    /**
     * 失败类型枚举名转中文描述。
     */
    static String failureText(String name) {
        if (name == null) {
            return null;
        }
        try {
            return BuildFailureTypeEnum.valueOf(name).getDescription();
        } catch (IllegalArgumentException e) {
            return name;
        }
    }
}
