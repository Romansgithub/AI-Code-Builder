package com.quan.aicodehelper.core.eval;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.mapper.CodeGenEvalRecordMapper;
import com.quan.aicodehelper.model.entity.CodeGenEvalRecord;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 代码生成质量指标采集器。
 *
 * <p><b>定位</b>：这是旁路逻辑，负责把每次生成的质量数据落库，
 * 用于事后统计「一次通过率 / 自愈成功率 / 失败原因分布」。</p>
 *
 * <p><b>核心约束</b>：采集失败绝不能影响生成主流程。
 * 写库异常只记日志并吞掉 —— 用户关心的是拿到代码，
 * 而不是平台的统计表是否完整。</p>
 *
 * <p>所有方法均为「永不抛出」语义。</p>
 */
@Slf4j
@Service
public class CodeGenEvalRecorder {

    /**
     * 失败原因在库中的截断长度，避免超长报错撑大表。
     */
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 1000;

    @Resource
    private CodeGenEvalRecordMapper evalRecordMapper;

    /**
     * 记录一次生成的质量指标。
     *
     * <p>永不抛异常。</p>
     *
     * @param metric 指标数据
     */
    public void record(GenerationMetric metric) {
        if (metric == null) {
            return;
        }
        try {
            CodeGenEvalRecord record = toEntity(metric);
            evalRecordMapper.insert(record);
            log.debug("已记录生成评测指标：appId={}, type={}, firstPass={}, attempts={}, finalPass={}",
                    metric.getAppId(), metric.getCodeGenType(),
                    metric.getFirstBuildPassed(), metric.getHealingAttempts(), metric.getFinalPassed());
        } catch (Exception e) {
            log.warn("记录生成评测指标失败（不影响主流程）：{}", e.getMessage());
        }
    }

    private CodeGenEvalRecord toEntity(GenerationMetric metric) {
        LocalDateTime now = LocalDateTime.now();
        return CodeGenEvalRecord.builder()
                .appId(metric.getAppId())
                .batchId(metric.getBatchId())
                .userPrompt(StrUtil.maxLength(metric.getUserPrompt(), 2000))
                .codeGenType(metric.getCodeGenType())
                .firstBuildPassed(metric.getFirstBuildPassed())
                .healingAttempts(metric.getHealingAttempts() == null ? 0 : metric.getHealingAttempts())
                .finalPassed(metric.getFinalPassed())
                .failureType(metric.getFailureType())
                .failureMessage(StrUtil.maxLength(metric.getFailureMessage(), FAILURE_MESSAGE_MAX_LENGTH))
                .generateCostMs(metric.getGenerateCostMs())
                .buildCostMs(metric.getBuildCostMs())
                .totalCostMs(metric.getTotalCostMs())
                .fileCount(metric.getFileCount())
                .totalBytes(metric.getTotalBytes())
                .userId(metric.getUserId())
                .createTime(now)
                .updateTime(now)
                .isDelete(0)
                .build();
    }

    /**
     * 统计产物规模（文件数与总字节数）。
     *
     * <p>用于评估「生成的代码量」这一维度，失败时返回 null 而非抛异常。</p>
     *
     * @param outputDir 产物目录，可为 null
     * @return 长度为 2 的数组 [文件数, 总字节数]，目录无效时返回 [0, 0]
     */
    public static long[] measureOutput(String outputDir) {
        if (StrUtil.isBlank(outputDir)) {
            return new long[]{0L, 0L};
        }
        File dir = new File(outputDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return new long[]{0L, 0L};
        }
        try {
            // 排除 node_modules 与 dist：它们不是「AI 生成的代码」，计入会严重失真
            Collection<File> files = FileUtil.loopFiles(dir, file -> {
                String path = file.getAbsolutePath();
                return !path.contains("node_modules") && !path.contains(File.separator + "dist" + File.separator);
            });
            long count = 0;
            long bytes = 0;
            for (File f : files) {
                if (f.isFile()) {
                    count++;
                    bytes += f.length();
                }
            }
            return new long[]{count, bytes};
        } catch (Exception e) {
            log.warn("统计产物规模失败：{}", e.getMessage());
            return new long[]{0L, 0L};
        }
    }
}
