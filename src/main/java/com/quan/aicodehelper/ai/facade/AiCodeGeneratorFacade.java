package com.quan.aicodehelper.ai.facade;

import com.quan.aicodehelper.ai.factory.AiCodeGeneratorServiceFactory;
import com.quan.aicodehelper.ai.service.AiCodeGeneratorService;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.eval.CodeGenEvalRecorder;
import com.quan.aicodehelper.core.eval.EvalScope;
import com.quan.aicodehelper.core.eval.GenerationMetric;
import com.quan.aicodehelper.core.healing.BuildSelfHealingService;
import com.quan.aicodehelper.core.healing.HealingOutcome;
import com.quan.aicodehelper.core.rag.CodeContextProvider;
import com.quan.aicodehelper.core.rag.CodeIndexService;
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

@Service
@Slf4j
public class AiCodeGeneratorFacade {

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private BuildSelfHealingService buildSelfHealingService;

    @Resource
    private GenerationProgressRegistry generationProgressRegistry;

    /**
     * 旁路埋点：它挂掉不能影响用户拿到代码，{@link CodeGenEvalRecorder#record(GenerationMetric)} 永不抛出。
     */
    @Resource
    private CodeGenEvalRecorder codeGenEvalRecorder;

    /** LLM 调用预算闸门。挂在本类而非各调用点：本类是「生成类调用」的唯一交汇处，一处覆盖全部路径，新增路径不会漏账。 */
    @Resource
    private LlmCallGuard llmCallGuard;

    /**
     * 检索增强：把项目现有代码里与需求相关的片段注入模型的 system message。
     * <p>挂在「生成类调用」的唯一交汇处即可覆盖全部路径；评测跑批用负数 appId（见 {@code EvalRunner}），
     * 会自然退化为无增强，既有评测口径不受影响。检索失败只意味着本轮没有增强，绝不抛异常。</p>
     */
    @Resource
    private CodeContextProvider codeContextProvider;

    @Resource
    private CodeIndexService codeIndexService;

    /**
     * 处理代码流通用方法。
     * <p>这里是「生成 → 保存 / 构建」的总入口，也是唯一能同时拿到两段耗时的地方：计时从这里开始，
     * 流消费完成即生成阶段结束，构建耗时另行统计，故评测埋点放在这一层而非散落到各分支。</p>
     */

    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenTypeEnum,
                                           Long appId, boolean inEvalScope) {
        StringBuilder codeBuilder = new StringBuilder();
        long startedAt = System.currentTimeMillis();
        return codeStream.doOnNext(chunk -> {
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            long generateCostMs = System.currentTimeMillis() - startedAt;
            if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT)
            {
                // 评测跑批自己会同步跑「构建 + 自愈」，此处让路，避免并发抢 node_modules / dist。
                if (inEvalScope) {
                    return;
                }
                // Vue 工程走「异步构建 + 自动修复」，npm 构建耗时以分钟计，不能阻塞 SSE 流。
                String projectPath = AppConstant.getCodeOutputRootDir() + File.separator
                        + codeGenTypeEnum.getValue() + "_" + appId;
                buildVueProjectAsync(projectPath, appId, generateCostMs);
                indexAfterGeneration(appId, codeGenTypeEnum, inEvalScope);
                return;
            }
            recordSaveOutcome(codeBuilder.toString(), codeGenTypeEnum, appId, generateCostMs,
                    inEvalScope);
            indexAfterGeneration(appId, codeGenTypeEnum, inEvalScope);
        });
    }

    /**
     * 解析并保存代码并上报评测指标。HTML / 多文件无构建步骤，一次通过率退化为解析与保存是否成功。
     * 评测跑批期间只保存不上报（{@code EvalRunner} 统一落库，重复记会污染口径）。
     */
    private void recordSaveOutcome(String completeCode, CodeGenTypeEnum codeGenTypeEnum,
                                   Long appId, long generateCostMs, boolean inEvalScope) {
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

        if (inEvalScope) {
            return;
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

    /** 上报指标，但埋点不可反噬主流程：流式回调抛出的异常会被 Reactor 转成 {@code onError} 中断整条 SSE 流，故再兜一层。 */
    private void recordQuietly(GenerationMetric metric) {
        try {
            codeGenEvalRecorder.record(metric);
        } catch (Exception e) {
            log.warn("上报生成评测指标失败（不影响主流程）：{}", e.getMessage());
        }
    }

    /**
     * 异步构建 Vue 工程，失败时自动触发自愈。
     * <p>用虚拟线程承载：构建 + 自愈可能持续数分钟，占用平台线程会拖垮线程池；异常必须在此兜底，
     * 虚拟线程内抛出的异常无人接收，会被静默吞掉。{@code generateCostMs} 为代码生成阶段耗时。</p>
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
     * <p>放在 {@code finally} 里：自愈成功、失败、流程抛异常都要留记录，异常路径是最需要被统计的样本。</p>
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
     * 取本次生成要注入的项目现有代码上下文。
     * <p>只在「已有应用」上检索：负数 appId 是评测会话（见 {@code EvalRunner}），既无历史代码，
     * 也不该让检索影响评测结果，否则同一评测集跨批次不可比。检索发生时要告知用户，
     * 静默增强会让效果差异无从解释（与模型降级一致，见 {@code notifyFallback}）。</p>
     */
    private String resolveProjectContext(Long appId, CodeGenTypeEnum type, String userMessage) {
        if (appId == null || appId <= 0) {
            return "";
        }
        String context = codeContextProvider.buildContext(appId, type, userMessage);
        if (context != null && !context.isBlank()) {
            generationProgressRegistry.emit(appId,
                    "已从项目现有代码中检索到相关片段，本次修改将基于这些真实代码进行");
            return context;
        }
        return "";
    }

    /**
     * 触发索引预热。调用点在流完成回调里，异常会被 Reactor 转成
     * {@code onError} 打断已生成结果，故再兜一层；索引是派生数据，绝不能伤害主流程。
     */
    private void indexAfterGeneration(Long appId, CodeGenTypeEnum type, boolean inEvalScope) {
        if (inEvalScope || appId == null) {
            return;
        }
        try {
            codeIndexService.indexAfterGeneration(appId, type);
        } catch (Exception e) {
            log.warn("触发代码索引预热失败（不影响主流程）：{}", e.getMessage());
        }
    }

    /** 根据用户消息生成代码流。 */
    public Flux<String> generateCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "代码生成类型不能为空");
        }
        // 闸门必须在任何模型调用之前，否则成了「先花钱再记账」；漏一次账会在后续每次调用上重复漏，账目永远不可信。
        llmCallGuard.acquire("代码生成-" + codeGenTypeEnum.getValue());
        // 必须在调用方线程就把「本次生成是否属于评测」定下来：EvalScope 是 ThreadLocal，
        // 而流完成回调未必还跑在那条线程上，到回调里再读恒为 false。
        boolean inEvalScope = EvalScope.isActive();
        // 检索放在预算闸门之后：额度告急时不该先花向量化的钱再去撞墙，且须在定下 inEvalScope 之后。
        String projectContext = resolveProjectContext(appId, codeGenTypeEnum, userMessage);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(appId, projectContext, userMessage);
                yield processCodeStream(codeStream, codeGenTypeEnum, appId, inEvalScope);
            }
            case MULTI_FILE -> {
                AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(appId, projectContext, userMessage);
                yield processCodeStream(codeStream, codeGenTypeEnum, appId, inEvalScope);
            }
            case VUE_PROJECT -> {
                AiCodeGeneratorService service  = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
                Flux<String> codeStream = service.generateVueProjectCodeStream(appId, projectContext, userMessage);
                yield processCodeStream(codeStream, codeGenTypeEnum, appId, inEvalScope);
            }
            default -> {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
            }
        };
    }
}
