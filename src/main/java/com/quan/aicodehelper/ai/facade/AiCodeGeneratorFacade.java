package com.quan.aicodehelper.ai.facade;

import com.quan.aicodehelper.ai.factory.AiCodeGeneratorServiceFactory;
import com.quan.aicodehelper.ai.service.AiCodeGeneratorService;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.eval.CodeGenEvalRecorder;
import com.quan.aicodehelper.core.eval.GenerationMetric;
import com.quan.aicodehelper.core.healing.BuildSelfHealingService;
import com.quan.aicodehelper.core.healing.HealingOutcome;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.ai.parser.CodeParserExecutor;
import com.quan.aicodehelper.ai.saver.CodeFileSaverExecutor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.io.File;

/**
 * 代码流工厂类
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    /**
     * 构建自愈服务：构建失败时让 AI 自动修复。
     */
    @Resource
    private BuildSelfHealingService buildSelfHealingService;

    @Resource
    private GenerationProgressRegistry generationProgressRegistry;

    /**
     * 生成质量指标采集器。
     *
     * <p>旁路埋点：它挂掉不能影响用户拿到代码，因此
     * {@link CodeGenEvalRecorder#record(GenerationMetric)} 是「永不抛出」语义。</p>
     */
    @Resource
    private CodeGenEvalRecorder codeGenEvalRecorder;

    /**
     * LLM 调用预算闸门。
     *
     * <p>装在这一层而不是各调用点的原因：这里是「生成类调用」的唯一交汇处 ——
     * 用户生成、构建自愈、评测跑批三条路径最终都要经过
     * {@link #generateCodeStream}。装在这里可以用一处代码覆盖三条路径，
     * 将来新增路径也不会漏账。</p>
     */
    @Resource
    private LlmCallGuard llmCallGuard;

    /**
     * 处理代码流通用方法
     *
     * <p>这里是「生成 → 保存 / 构建」的总入口，也是唯一能同时拿到两段耗时的地方：
     * 进入方法时开始计时，流消费完成即为生成阶段结束，之后的构建耗时单独统计。
     * 因此评测埋点放在这一层，而不是散落到各个分支里。</p>
     *
     * @param codeStream
     * @param codeGenTypeEnum
     * @return
     */

    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        long startedAt = System.currentTimeMillis();
        return codeStream.doOnNext(chunk -> {
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            long generateCostMs = System.currentTimeMillis() - startedAt;
            if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT)
            {
                // Vue 工程走「异步构建 + 自动修复」：构建本身要跑 npm，耗时以分钟计，
                // 不能阻塞 SSE 流；自愈过程中通过 GenerationProgressRegistry 推送进度，
                // 用户能看到「正在修复（第 1/2 次）」这类实时反馈。
                String projectPath = AppConstant.getCodeOutputRootDir() + File.separator
                        + codeGenTypeEnum.getValue() + "_" + appId;
                buildVueProjectAsync(projectPath, appId, generateCostMs);
                return;
            }
            recordSaveOutcome(codeBuilder.toString(), codeGenTypeEnum, appId, generateCostMs);
        });
    }

    /**
     * 解析并保存代码，同时上报评测指标。
     *
     * <p>HTML / 多文件类型没有构建步骤，因此「一次通过率」在这里退化为
     * 「解析与保存是否成功」—— 解析失败同样意味着这次生成对用户不可用。</p>
     */
    private void recordSaveOutcome(String completeCode, CodeGenTypeEnum codeGenTypeEnum,
                                   Long appId, long generateCostMs) {
        boolean saved = false;
        String failureMessage = null;
        String saveDirPath = null;
        try {
            Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenTypeEnum);
            File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenTypeEnum, appId);
            saveDirPath = saveDir.getAbsolutePath();
            saved = true;
            log.info("保存成功，路径为： " + saveDirPath);
        } catch (Exception e) {
            failureMessage = e.getMessage();
            log.error("保存失败：{}", failureMessage);
        }

        GenerationMetric metric = GenerationMetric.builder()
                .appId(appId)
                .codeGenType(codeGenTypeEnum.getValue())
                .firstBuildPassed(saved)
                .finalPassed(saved)
                .healingAttempts(0)
                .generateCostMs(generateCostMs)
                .buildCostMs(0L)
                .totalCostMs(generateCostMs)
                .build();
        if (!saved) {
            metric.withFailure(BuildFailureTypeEnum.MISSING_DIST, failureMessage);
        } else {
            metric.withOutput(saveDirPath);
        }
        recordQuietly(metric);
    }

    /**
     * 上报指标，并保证埋点绝不会反噬主流程。
     *
     * <p>{@link CodeGenEvalRecorder#record} 自身已做容错，但这里是流式回调 ——
     * 回调里抛出的异常会被 Reactor 转成 {@code onError} 信号，
     * 直接让整条 SSE 流中断，用户代码都收不到。
     * 因此在这一层再兜一次，把「埋点失败」和「代码生成失败」彻底隔开。</p>
     */
    private void recordQuietly(GenerationMetric metric) {
        try {
            codeGenEvalRecorder.record(metric);
        } catch (Exception e) {
            log.warn("上报生成评测指标失败（不影响主流程）：{}", e.getMessage());
        }
    }

    /**
     * 异步构建 Vue 工程，失败时自动触发自愈。
     *
     * <p>用虚拟线程承载：构建 + 自愈可能持续数分钟，占用平台线程会拖垮线程池。
     * 异常必须在此处兜底 —— 虚拟线程内抛出的异常无人接收，会被静默吞掉。</p>
     *
     * @param generateCostMs 代码生成阶段耗时，用于拼出端到端总耗时
     */
    private void buildVueProjectAsync(String projectPath, Long appId, long generateCostMs) {
        Thread.ofVirtual().name("vue-build-heal-" + appId).start(() -> {
            HealingOutcome outcome = null;
            String unexpectedError = null;
            try {
                outcome = buildSelfHealingService.buildWithHealingOutcome(projectPath, appId);
                BuildResult result = outcome.getBuildResult();
                if (result.isSuccess()) {
                    log.info("应用 {} 的 Vue 工程构建完成", appId);
                } else {
                    log.warn("应用 {} 的 Vue 工程构建失败（{}）：{}", appId,
                            result.getFailureType(), result.getErrorMessage());
                }
            } catch (Exception e) {
                unexpectedError = e.getMessage();
                log.error("应用 {} 的构建自愈流程异常：{}", appId, unexpectedError, e);
                generationProgressRegistry.emit(appId, "项目构建过程中发生异常，请稍后重试");
            } finally {
                recordBuildOutcome(appId, projectPath, generateCostMs, outcome, unexpectedError);
            }
        });
    }

    /**
     * 上报 Vue 工程这次的生成 + 构建结果。
     *
     * <p>放在 {@code finally} 里：无论是自愈成功、自愈失败还是流程本身抛异常，
     * 都要留下一条记录 —— 异常路径恰恰是最需要被统计的那些样本。</p>
     */
    private void recordBuildOutcome(Long appId, String projectPath, long generateCostMs,
                                    HealingOutcome outcome, String unexpectedError) {
        long buildCostMs = outcome == null ? 0L : outcome.getBuildCostMs();

        GenerationMetric metric = GenerationMetric.builder()
                .appId(appId)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue())
                .firstBuildPassed(outcome != null && outcome.isFirstBuildPassed())
                .finalPassed(outcome != null && outcome.isFinalPassed())
                .healingAttempts(outcome == null ? 0 : outcome.getHealingAttempts())
                .generateCostMs(generateCostMs)
                .buildCostMs(buildCostMs)
                .totalCostMs(generateCostMs + buildCostMs)
                .build();

        if (outcome != null && !outcome.isFinalPassed()) {
            BuildResult result = outcome.getBuildResult();
            metric.withFailure(
                    result == null ? BuildFailureTypeEnum.EXECUTION_ERROR : result.getFailureType(),
                    result == null ? null : result.getErrorMessage());
        } else if (outcome == null) {
            metric.withFailure(BuildFailureTypeEnum.EXECUTION_ERROR, unexpectedError);
        } else {
            metric.withOutput(projectPath);
        }
        recordQuietly(metric);
    }

    /**
     * 根据用户消息生成代码流
     *
     * @param userMessage
     * @param codeGenTypeEnum
     * @return
     */
    public Flux<String> generateCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "代码生成类型不能为空");
        }
        // 闸门必须在任何模型调用之前 —— 顺序反了就变成「先花钱再记账」。
        // 裸奔出去（fail-open + 日志）而不是放行，是因为漏一次账就
        // 会在后续每一次调用上重复漏，账目永远不可信。
        llmCallGuard.acquire("代码生成-" + codeGenTypeEnum.getValue());
        return switch (codeGenTypeEnum) {
            case HTML -> {
                AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(appId, userMessage);
                yield processCodeStream(codeStream, codeGenTypeEnum, appId);
            }
            case MULTI_FILE -> {
                AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(appId, userMessage);
                yield processCodeStream(codeStream, codeGenTypeEnum, appId);
            }
            case VUE_PROJECT -> {
                AiCodeGeneratorService service  = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
                Flux<String> codeStream = service.generateVueProjectCodeStream(appId, userMessage);
                yield processCodeStream(codeStream, codeGenTypeEnum, appId);
            }
            default -> {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
            }
        };
    }
}
