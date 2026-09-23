package com.quan.aicodehelper.service.impl;

import com.quan.aicodehelper.mapper.CodeGenEvalRecordMapper;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.model.vo.EvalReportVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测报告服务测试：把 SQL 聚合结果翻译成带比率的视图对象，重点验证除零、
 * null 语义与驱动返回类型不统一时的数值转换。
 */
@DisplayName("评测报告服务")
class EvalReportServiceTest {

    private CodeGenEvalRecordMapper mapper;
    private EvalReportService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CodeGenEvalRecordMapper.class);
        service = new EvalReportService();
        ReflectionTestUtils.setField(service, "evalRecordMapper", mapper);

        // 默认都返回空，各用例按需覆盖
        when(mapper.summaryOverall(any(), any())).thenReturn(null);
        when(mapper.summaryByCodeGenType(any(), any())).thenReturn(Collections.emptyList());
        when(mapper.failureDistribution(any(), any(), anyInt())).thenReturn(Collections.emptyList());
        when(mapper.healingAttemptDistribution(any(), any())).thenReturn(Collections.emptyList());
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Nested
    @DisplayName("比率计算")
    class RateCalc {

        @Test
        @DisplayName("分母为 0 时应返回 0 而不是抛异常或 NaN")
        void shouldReturnZeroWhenDenominatorZero() {
            assertThat(EvalReportService.rate(0, 0)).isZero();
            assertThat(EvalReportService.rate(5, 0)).isZero();
        }

        @Test
        @DisplayName("应保留一位小数")
        void shouldRoundToOneDecimal() {
            // 1/3 = 33.333... → 33.3
            assertThat(EvalReportService.rate(1, 3)).isEqualTo(33.3);
            // 2/3 = 66.666... → 66.7
            assertThat(EvalReportService.rate(2, 3)).isEqualTo(66.7);
        }

        @Test
        @DisplayName("可空比率：分母为 0 时返回 null，表示「无数据」而非「0%」")
        void nullableRateShouldDistinguishNoDataFromZero() {
            assertThat(EvalReportService.nullableRate(0, 0)).isNull();
            // 有数据但结果真的是 0%，应返回 0.0 而不是 null
            assertThat(EvalReportService.nullableRate(0, 3)).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("枚举翻译")
    class EnumText {

        @Test
        @DisplayName("生成类型 value 应翻成中文名")
        void shouldTranslateCodeGenType() {
            assertThat(EvalReportService.typeText("vue_project")).contains("Vue");
            assertThat(EvalReportService.typeText("html")).isNotBlank();
        }

        @Test
        @DisplayName("未知类型应原样返回，便于发现新增类型未登记")
        void shouldPassThroughUnknownType() {
            assertThat(EvalReportService.typeText("brand_new_type")).isEqualTo("brand_new_type");
            assertThat(EvalReportService.typeText(null)).isNull();
        }

        @Test
        @DisplayName("失败类型枚举名应翻成中文描述")
        void shouldTranslateFailureType() {
            assertThat(EvalReportService.failureText(BuildFailureTypeEnum.COMPILE_ERROR.name()))
                    .isEqualTo(BuildFailureTypeEnum.COMPILE_ERROR.getDescription());
        }

        @Test
        @DisplayName("历史数据里的未知失败类型应原样返回而不是抛异常")
        void shouldPassThroughUnknownFailureType() {
            // 枚举被改过名、或旧数据残留时的保护
            assertThat(EvalReportService.failureText("SOME_OLD_TYPE")).isEqualTo("SOME_OLD_TYPE");
            assertThat(EvalReportService.failureText(null)).isNull();
        }
    }

    @Nested
    @DisplayName("总体指标组装")
    class OverallAssembly {

        @Test
        @DisplayName("应正确算出各项比率")
        void shouldComputeRates() {
            when(mapper.summaryOverall(any(), any())).thenReturn(row(
                    "totalCount", 10L,
                    "firstPassCount", 6L,
                    "finalPassCount", 9L,
                    "healingUsedCount", 4L,
                    "healedSuccessCount", 3L,
                    "avgTotalCostMs", 12_500L));

            EvalReportVO vo = service.buildReport(null, null);

            assertThat(vo.getOverall().getTotalCount()).isEqualTo(10);
            assertThat(vo.getOverall().getFirstPassCount()).isEqualTo(6);
            assertThat(vo.getOverall().getFirstPassRate()).isEqualTo(60.0);
            assertThat(vo.getOverall().getFinalPassRate()).isEqualTo(90.0);
            assertThat(vo.getOverall().getHealingSuccessRate()).isEqualTo(75.0);
            assertThat(vo.getOverall().getAvgTotalCostMs()).isEqualTo(12_500L);
        }

        @Test
        @DisplayName("查询无结果时应返回全 0 的空报告，不抛异常")
        void shouldHandleNullRow() {
            EvalReportVO vo = service.buildReport(null, null);

            assertThat(vo.getOverall().getTotalCount()).isZero();
            assertThat(vo.getOverall().getFirstPassRate()).isZero();
            assertThat(vo.getOverall().getHealingSuccessRate()).isNull();
        }

        @Test
        @DisplayName("无用例触发自愈时，自愈成功率应为 null 而不是 0")
        void shouldReportNullHealingRateWhenNeverUsed() {
            when(mapper.summaryOverall(any(), any())).thenReturn(row(
                    "totalCount", 5L, "firstPassCount", 5L, "finalPassCount", 5L,
                    "healingUsedCount", 0L, "healedSuccessCount", 0L, "avgTotalCostMs", 1000L));

            EvalReportVO vo = service.buildReport(null, null);

            // null 表示「没机会自愈」，与「自愈全失败」是两回事
            assertThat(vo.getOverall().getHealingSuccessRate()).isNull();
        }

        @Test
        @DisplayName("SUM 返回 null（全表无记录）时应按 0 处理")
        void shouldTreatNullSumAsZero() {
            // MySQL 的 SUM 在没有匹配行时返回 NULL，而非 0
            when(mapper.summaryOverall(any(), any())).thenReturn(row(
                    "totalCount", 3L, "firstPassCount", null, "finalPassCount", null,
                    "healingUsedCount", null, "healedSuccessCount", null, "avgTotalCostMs", null));

            EvalReportVO vo = service.buildReport(null, null);

            assertThat(vo.getOverall().getFirstPassCount()).isZero();
            assertThat(vo.getOverall().getFirstPassRate()).isZero();
            assertThat(vo.getOverall().getAvgTotalCostMs()).isZero();
        }

        @Test
        @DisplayName("AVG 返回 BigDecimal 时应能正确转换，不因带小数而崩")
        void shouldHandleBigDecimalAvg() {
            // AVG 在不同驱动下可能是 Long、BigDecimal 或 String，这里覆盖最麻烦的一种
            when(mapper.summaryOverall(any(), any())).thenReturn(row(
                    "totalCount", 2L, "firstPassCount", 2L, "finalPassCount", 2L,
                    "healingUsedCount", 0L, "healedSuccessCount", 0L,
                    "avgTotalCostMs", new BigDecimal("12345.67")));

            EvalReportVO vo = service.buildReport(null, null);

            assertThat(vo.getOverall().getAvgTotalCostMs()).isEqualTo(12_345L);
        }
    }

    @Nested
    @DisplayName("分类型指标")
    class ByTypeAssembly {

        @Test
        @DisplayName("应逐类型组装并翻译类型名")
        void shouldAssembleEachType() {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(row("codeGenType", "vue_project", "totalCount", 4L, "firstPassCount", 2L,
                    "finalPassCount", 3L, "healingUsedCount", 2L, "healedSuccessCount", 1L,
                    "avgTotalCostMs", 60_000L));
            rows.add(row("codeGenType", "html", "totalCount", 4L, "firstPassCount", 4L,
                    "finalPassCount", 4L, "healingUsedCount", 0L, "healedSuccessCount", 0L,
                    "avgTotalCostMs", 8_000L));
            when(mapper.summaryByCodeGenType(any(), any())).thenReturn(rows);

            EvalReportVO vo = service.buildReport(null, null);

            assertThat(vo.getByType()).hasSize(2);
            EvalReportVO.ByType vue = vo.getByType().get(0);
            assertThat(vue.getCodeGenType()).isEqualTo("vue_project");
            assertThat(vue.getCodeGenTypeText()).contains("Vue");
            assertThat(vue.getFirstPassRate()).isEqualTo(50.0);
            assertThat(vue.getFinalPassRate()).isEqualTo(75.0);
            assertThat(vue.getHealingSuccessRate()).isEqualTo(50.0);

            // HTML 从不触发自愈 → null
            assertThat(vo.getByType().get(1).getHealingSuccessRate()).isNull();
        }
    }

    @Nested
    @DisplayName("失败分布")
    class FailureDistribution {

        @Test
        @DisplayName("占比应以全部失败数为分母")
        void shouldComputeShareOfAllFailures() {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(row("failureType", "COMPILE_ERROR", "failureCount", 6L));
            rows.add(row("failureType", "DEPENDENCY_INSTALL", "failureCount", 2L));
            when(mapper.failureDistribution(any(), any(), anyInt())).thenReturn(rows);

            EvalReportVO vo = service.buildReport(null, null);

            assertThat(vo.getFailures()).hasSize(2);
            assertThat(vo.getFailures().get(0).getFailureCount()).isEqualTo(6);
            // 6/8 = 75%
            assertThat(vo.getFailures().get(0).getRatio()).isEqualTo(75.0);
            assertThat(vo.getFailures().get(1).getRatio()).isEqualTo(25.0);
            assertThat(vo.getFailures().get(0).getFailureTypeText())
                    .isEqualTo(BuildFailureTypeEnum.COMPILE_ERROR.getDescription());
        }
    }

    @Nested
    @DisplayName("自愈轮数分布")
    class HealingDistribution {

        @Test
        @DisplayName("应算出各轮数下的通过率，回答问题「修几轮一般能成」")
        void shouldComputePassRatePerAttempt() {
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(row("healingAttempts", 0, "recordCount", 10L, "passCount", 10L));
            rows.add(row("healingAttempts", 1, "recordCount", 4L, "passCount", 3L));
            rows.add(row("healingAttempts", 2, "recordCount", 4L, "passCount", 1L));
            when(mapper.healingAttemptDistribution(any(), any())).thenReturn(rows);

            EvalReportVO vo = service.buildReport(null, null);

            assertThat(vo.getHealingAttempts()).hasSize(3);
            assertThat(vo.getHealingAttempts().get(0).getHealingAttempts()).isZero();
            assertThat(vo.getHealingAttempts().get(0).getPassRate()).isEqualTo(100.0);
            assertThat(vo.getHealingAttempts().get(1).getPassRate()).isEqualTo(75.0);
            assertThat(vo.getHealingAttempts().get(2).getPassRate()).isEqualTo(25.0);
        }
    }

    @Nested
    @DisplayName("查询口径")
    class Scope {

        @Test
        @DisplayName("传了 batchId 时应作为过滤条件传给 mapper")
        void shouldPassBatchIdToMapper() {
            service.buildReport(7, "eval-abc123");

            org.mockito.Mockito.verify(mapper).summaryOverall(any(), org.mockito.ArgumentMatchers.eq("eval-abc123"));
            org.mockito.Mockito.verify(mapper).summaryByCodeGenType(any(),
                    org.mockito.ArgumentMatchers.eq("eval-abc123"));
        }

        @Test
        @DisplayName("sinceDays 为 null 或非正数时应按全量查询（since 传 null）")
        void shouldTreatNonPositiveDaysAsAllTime() {
            service.buildReport(null, null);
            org.mockito.Mockito.verify(mapper).summaryOverall(isNull(), isNull());

            service.buildReport(0, null);
            org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(2)).summaryOverall(isNull(), isNull());

            service.buildReport(-1, null);
            org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(3)).summaryOverall(isNull(), isNull());
        }

        @Test
        @DisplayName("传了 sinceDays 时应算出起始时间并说明统计口径")
        void shouldDescribeScope() {
            EvalReportVO vo = service.buildReport(30, null);

            assertThat(vo.getScope()).contains("最近 30 天");
            assertThat(vo.getSince()).isNotBlank();
        }

        @Test
        @DisplayName("按批次查询时口径说明应指明批次")
        void shouldDescribeBatchScope() {
            EvalReportVO vo = service.buildReport(null, "eval-xyz");

            assertThat(vo.getScope()).contains("eval-xyz");
            assertThat(vo.getBatchId()).isEqualTo("eval-xyz");
        }
    }
}
