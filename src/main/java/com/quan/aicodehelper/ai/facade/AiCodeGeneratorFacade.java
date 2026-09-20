package com.quan.aicodehelper.ai.facade;

import com.quan.aicodehelper.ai.factory.AiCodeGeneratorServiceFactory;
import com.quan.aicodehelper.ai.service.AiCodeGeneratorService;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.healing.BuildSelfHealingService;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
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
     * 处理代码流通用方法
     *
     * @param codeStream
     * @param codeGenTypeEnum
     * @return
     */

    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenTypeEnum, Long appId) {
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream.doOnNext(chunk -> {
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT)
            {
                // Vue 工程走「异步构建 + 自动修复」：构建本身要跑 npm，耗时以分钟计，
                // 不能阻塞 SSE 流；自愈过程中通过 GenerationProgressRegistry 推送进度，
                // 用户能看到「正在修复（第 1/2 次）」这类实时反馈。
                String projectPath = AppConstant.getCodeOutputRootDir() + File.separator
                        + codeGenTypeEnum.getValue() + "_" + appId;
                buildVueProjectAsync(projectPath, appId);
                return;
            }
            try {
                String completeCode = codeBuilder.toString();
                Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenTypeEnum);
                File saveDir = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenTypeEnum, appId);
                log.info("保存成功，路径为： " + saveDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存失败：{}", e.getMessage());
            }
        });
    }

    /**
     * 异步构建 Vue 工程，失败时自动触发自愈。
     *
     * <p>用虚拟线程承载：构建 + 自愈可能持续数分钟，占用平台线程会拖垮线程池。
     * 异常必须在此处兜底 —— 虚拟线程内抛出的异常无人接收，会被静默吞掉。</p>
     */
    private void buildVueProjectAsync(String projectPath, Long appId) {
        Thread.ofVirtual().name("vue-build-heal-" + appId).start(() -> {
            try {
                BuildResult result = buildSelfHealingService.buildWithHealing(projectPath, appId);
                if (result.isSuccess()) {
                    log.info("应用 {} 的 Vue 工程构建完成", appId);
                } else {
                    log.warn("应用 {} 的 Vue 工程构建失败（{}）：{}", appId,
                            result.getFailureType(), result.getErrorMessage());
                }
            } catch (Exception e) {
                log.error("应用 {} 的构建自愈流程异常：{}", appId, e.getMessage(), e);
                generationProgressRegistry.emit(appId, "项目构建过程中发生异常，请稍后重试");
            }
        });
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
