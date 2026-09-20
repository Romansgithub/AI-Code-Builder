package com.quan.aicodehelper.ai.factory;

import cn.hutool.core.bean.DynaBean;
import cn.hutool.extra.spring.SpringUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quan.aicodehelper.ai.guardrail.PromptSafetyInputGuardrail;
import com.quan.aicodehelper.ai.guardrail.RetryOutputGuardrail;
import com.quan.aicodehelper.ai.service.AiCodeGeneratorService;
import com.quan.aicodehelper.ai.tools.FileWriteTool;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.ai.tools.ToolManager;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.service.ChatHistoryService;
import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 聊天记忆工厂类
 */
@Slf4j
@Component
public class AiCodeGeneratorServiceFactory {

    @Resource(name = "openAiChatModel")
    private ChatModel chatModel;
    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;
    @Resource
    private ChatHistoryService chatHistoryService;
    @Resource
    private ToolManager toolManager;

    /**
     * 创建 AI 代码生成服务实例
     * @param appId
     * @param codeGenType
     * @return
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        MessageWindowChatMemory chatMemory =  createChatMemory(appId);
        return switch (codeGenType) {
            case VUE_PROJECT -> {
                StreamingChatModel reasoningStreamingChatModel = SpringUtil.getBean("reasoningStreamingChatModelPrototype", StreamingChatModel.class);
                    yield AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(reasoningStreamingChatModel)
                    .chatMemoryProvider(memoryId -> chatMemory)
                    .tools(toolManager.getAllTools())
                    .inputGuardrails(new PromptSafetyInputGuardrail())
//                    .outputGuardrails(new RetryOutputGuardrail())
                    .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(
                            toolExecutionRequest, "Error：Tool not found ：" + toolExecutionRequest.name())
                    )
                    .build();
            }
            case HTML, MULTI_FILE -> {
                StreamingChatModel openAiStreamingChatModel = SpringUtil.getBean("streamingChatModelPrototype", StreamingChatModel.class);
                yield AiServices.builder(AiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(openAiStreamingChatModel)
                    .chatMemoryProvider(memoryId -> chatMemory)
                    .inputGuardrails(new PromptSafetyInputGuardrail())
//                    .outputGuardrails(new RetryOutputGuardrail())
                    .build();
            }
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型:{}" + codeGenType.getValue());

        };
    }


    /**
     * 创建带 Redis 持久化的会话记忆，记忆为空（如过期）时从数据库聊天记录恢复
     * @param memoryId
     * @return
     */
    public MessageWindowChatMemory createChatMemory(Object memoryId) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .id(memoryId)
                .chatMemoryStore(redisChatMemoryStore)
                .maxMessages(20)
                .build();
        if (chatMemory.messages().isEmpty()) {
            chatHistoryService.loadChatHistoryToMemory((Long) memoryId, chatMemory, 20);
        }
        return chatMemory;
    }
    /**
     * 创建 AI 代码生成服务实例，并使用缓存进行管理
     */
    private final Cache<String, AiCodeGeneratorService> serviceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) -> {
                log.debug("AI 服务实例被移除， 缓存键： {}， 原因： {}", key, cause);
            })
            .build();

    /**
     * 获取 AI 代码生成服务实例
     * @param appId
     * @param codeGenTypeEnum
     * @return
     */
    public AiCodeGeneratorService getAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenTypeEnum)
    {
        String cacheKey = buildCacheKey(appId, codeGenTypeEnum);
        return serviceCache.get(cacheKey, key -> createAiCodeGeneratorService(appId, codeGenTypeEnum));
    }

    /**
     * 构建缓存键
     * @param appId
     * @param codeGenTypeEnum
     * @return
     */
    private String buildCacheKey(long appId, CodeGenTypeEnum codeGenTypeEnum)
    {
        return appId + "_" + codeGenTypeEnum.getValue();
    }

}
