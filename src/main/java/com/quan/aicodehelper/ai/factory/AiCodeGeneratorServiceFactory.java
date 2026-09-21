package com.quan.aicodehelper.ai.factory;

import cn.hutool.core.bean.DynaBean;
import cn.hutool.extra.spring.SpringUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.quan.aicodehelper.ai.config.ReasoningStreamingChatModelConfig;
import com.quan.aicodehelper.ai.config.StreamingChatModelConfig;
import com.quan.aicodehelper.ai.fallback.FallbackModelProvider;
import com.quan.aicodehelper.ai.fallback.FallbackStreamingChatModel;
import com.quan.aicodehelper.ai.guardrail.PromptSafetyInputGuardrail;
import com.quan.aicodehelper.ai.guardrail.RetryOutputGuardrail;
import com.quan.aicodehelper.ai.service.AiCodeGeneratorService;
import com.quan.aicodehelper.ai.service.AiCodeHealingService;
import com.quan.aicodehelper.ai.tools.FileWriteTool;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.ai.tools.ToolManager;
import com.quan.aicodehelper.config.ModelFallbackProperties;
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
    @Resource
    private FallbackModelProvider fallbackModelProvider;
    @Resource
    private StreamingChatModelConfig streamingChatModelConfig;
    @Resource
    private ReasoningStreamingChatModelConfig reasoningStreamingChatModelConfig;
    @Resource
    private ModelFallbackProperties modelFallbackProperties;
    @Resource
    private GenerationProgressRegistry generationProgressRegistry;

    /**
     * 创建 AI 代码生成服务实例
     * @param appId
     * @param codeGenType
     * @return
     */
    private AiCodeGeneratorService createAiCodeGeneratorService(long appId, CodeGenTypeEnum codeGenType) {
        // 走缓存取记忆而不是每次新建：自愈服务要用同一个记忆对象，
        // 两个实例各自 createChatMemory 会各持一份内存副本互相覆盖（见 resolveChatMemory）
        MessageWindowChatMemory chatMemory =  resolveChatMemory(appId);
        return switch (codeGenType) {
            case VUE_PROJECT -> {
                StreamingChatModel reasoningStreamingChatModel = SpringUtil.getBean("reasoningStreamingChatModelPrototype", StreamingChatModel.class);
                // 包一层降级：模型服务抖动时自动切备用模型，不必让整次生成白费
                StreamingChatModel withFallback = fallbackModelProvider.wrapWithFallback(
                        reasoningStreamingChatModel,
                        reasoningStreamingChatModelConfig.getModelName(),
                        reasoningStreamingChatModelConfig.getBaseUrl(),
                        reasoningStreamingChatModelConfig.getApiKey(),
                        reasoningStreamingChatModelConfig.getMaxTokens(),
                        reasoningStreamingChatModelConfig.getTemperature(),
                        reasoningStreamingChatModelConfig.isLogRequests(),
                        reasoningStreamingChatModelConfig.isLogResponses(),
                        event -> notifyFallback(appId, event));
                    yield AiServices.builder(AiCodeGeneratorService.class)
                    .streamingChatModel(withFallback)
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
                StreamingChatModel withFallback = fallbackModelProvider.wrapWithFallback(
                        openAiStreamingChatModel,
                        streamingChatModelConfig.getModelName(),
                        streamingChatModelConfig.getBaseUrl(),
                        streamingChatModelConfig.getApiKey(),
                        streamingChatModelConfig.getMaxTokens(),
                        streamingChatModelConfig.getTemperature(),
                        streamingChatModelConfig.isLogRequests(),
                        streamingChatModelConfig.isLogResponses(),
                        event -> notifyFallback(appId, event));
                yield AiServices.builder(AiCodeGeneratorService.class)
                    .chatModel(chatModel)
                    .streamingChatModel(withFallback)
                    .chatMemoryProvider(memoryId -> chatMemory)
                    .inputGuardrails(new PromptSafetyInputGuardrail())
//                    .outputGuardrails(new RetryOutputGuardrail())
                    .build();
            }
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型:{}" + codeGenType.getValue());

        };
    }

    /**
     * 创建构建自愈专用的 AI 服务实例。
     *
     * <p><b>与生成服务最关键的一处差异：不挂 {@code PromptSafetyInputGuardrail}。</b>
     * 那个护栏的作用对象是「用户在聊天框里敲的话」，而自愈的 user 消息是程序
     * 拼装的构建报错。挂上去的后果不是「多一层保护」：报错 573 字 + 模板 668 字
     * 超过 1000 字上限，请求直接被拒，异常又被吞成「这轮没修好」，
     * 于是报告上出现「自愈 2 轮」而模型一次都没被调用过。
     * 完整事故记录见 {@link AiCodeHealingService} 的类注释。</p>
     *
     * <p>其余配置（模型、降级包装、工具集、幻觉工具名兜底、会话记忆）与生成服务
     * 保持一致 —— 自愈要做的事就是「像刚才那样改代码」，能力上不该有差别。</p>
     */
    private AiCodeHealingService createAiCodeHealingService(long appId) {
        StreamingChatModel reasoningStreamingChatModel = SpringUtil.getBean("reasoningStreamingChatModelPrototype", StreamingChatModel.class);
        StreamingChatModel withFallback = fallbackModelProvider.wrapWithFallback(
                reasoningStreamingChatModel,
                reasoningStreamingChatModelConfig.getModelName(),
                reasoningStreamingChatModelConfig.getBaseUrl(),
                reasoningStreamingChatModelConfig.getApiKey(),
                reasoningStreamingChatModelConfig.getMaxTokens(),
                reasoningStreamingChatModelConfig.getTemperature(),
                reasoningStreamingChatModelConfig.isLogRequests(),
                reasoningStreamingChatModelConfig.isLogResponses(),
                event -> notifyFallback(appId, event));
        return AiServices.builder(AiCodeHealingService.class)
                .streamingChatModel(withFallback)
                .chatMemoryProvider(memoryId -> resolveChatMemory(appId))
                .tools(toolManager.getAllTools())
                .hallucinatedToolNameStrategy(toolExecutionRequest -> ToolExecutionResultMessage.from(
                        toolExecutionRequest, "Error：Tool not found ：" + toolExecutionRequest.name())
                )
                .build();
    }

    /**
     * 获取构建自愈专用服务实例（带缓存）。
     */
    public AiCodeHealingService getAiCodeHealingService(long appId) {
        String cacheKey = appId + "_" + HEALING_CACHE_SUFFIX;
        return healingServiceCache.get(cacheKey, key -> createAiCodeHealingService(appId));
    }

    /**
     * 降级发生时通知用户与日志。
     *
     * <p>为什么要把降级告诉用户：备用模型往往档位更低，输出质量可能与预期不同。
     * 静默降级会让用户困惑于「为什么这次生成得不一样」，甚至误判为模型变笨了。
     * 明说「已切换到备用模型」既是知情权，也是排查线索。</p>
     */
    private void notifyFallback(long appId, FallbackStreamingChatModel.FallbackEvent event) {
        log.warn("应用 {} 触发模型降级：{} → {}（原因：{}）",
                appId, event.fromModel(), event.toModel(), event.reason());
        if (modelFallbackProperties.isNotifyUser()) {
            generationProgressRegistry.emit(appId,
                    String.format("模型服务异常（%s），已自动切换到备用模型 %s",
                            event.reason(), event.toModel()));
        }
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
     * 取（或建）某个 appId 的会话记忆，生成与自愈共用同一份。
     *
     * <p>用缓存而不是每次新建，是因为记忆对象是有状态的：两个实例各持一份的话，
     * 每次 {@code add} 都是整份写回 Redis，后写的一方会覆盖掉另一方刚追加的消息。</p>
     */
    private MessageWindowChatMemory resolveChatMemory(long appId) {
        return chatMemoryCache.get(appId, this::createChatMemory);
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
     * 自愈服务缓存键后缀。
     *
     * <p>必须与生成服务分开缓存：两者是不同的返回类型、不同的护栏配置，
     * 若共用同一个 key，Caffeine 会在第二次 get 时直接抛 ClassCastException。</p>
     */
    private static final String HEALING_CACHE_SUFFIX = "HEALING";

    /**
     * 构建自愈服务缓存，与生成服务缓存同样的过期策略。
     */
    private final Cache<String, AiCodeHealingService> healingServiceCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
            .removalListener((key, value, cause) -> {
                log.debug("自愈服务实例被移除， 缓存键： {}， 原因： {}", key, cause);
            })
            .build();

    /**
     * 会话记忆缓存。
     *
     * <p><b>为什么需要单独缓存</b>：生成与自愈是两个不同的 {@code AiService} 实例，
     * 但它们必须共享同一个 {@code appId} 的对话记忆 —— 自愈要能看到模型刚才写的文件。
     * 若两边各自 {@code createChatMemory}，就会得到两个指向同一个 Redis key
     * 的独立内存对象：各自从 Redis 载入一份、各自整份写回，
     * 后写的一方会把对方期间追加的消息抹掉（写入是整份覆盖而非增量追加）。</p>
     *
     * <p>过期策略与两个服务缓存保持一致，避免出现「服务实例还在、
     * 它持有的记忆对象已经过期」这种半新半旧的组合。</p>
     */
    private final Cache<Long, MessageWindowChatMemory> chatMemoryCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .expireAfterAccess(Duration.ofMinutes(10))
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
