package com.quan.aicodehelper.ai.fallback;

import com.quan.aicodehelper.config.ModelFallbackProperties;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 按配置装配带降级能力的流式模型。
 *
 * <p><b>为什么单独抽一个类而不是在 Config 里拼</b>：候选的构造需要读配置、
 * 判断字段覆盖、处理非法值，逻辑量已经超过「Bean 定义」该承担的范围。
 * 而且它需要被多处复用（三种生成模型各自要一份候选链），
 * 抽出来才能避免复制粘贴。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FallbackModelProvider {

    private final ModelFallbackProperties fallbackProperties;

    /**
     * 组装带降级的流式模型。
     *
     * @param primary              主模型
     * @param primaryDisplayName   主模型展示名（用于日志与前端提示）
     * @param primaryBaseUrl       主模型 base-url，候选未覆盖时沿用
     * @param primaryApiKey        主模型 api-key，候选未覆盖时沿用
     * @param primaryMaxTokens     主模型 max-tokens，候选未覆盖时沿用
     * @param primaryTemperature   主模型 temperature
     * @param logRequests          是否记录请求日志
     * @param logResponses         是否记录响应日志
     * @param onFallback           降级回调
     * @return 带降级能力的模型；未配置候选时原样返回主模型（不引入额外包装）
     */
    public StreamingChatModel wrapWithFallback(StreamingChatModel primary,
                                               String primaryDisplayName,
                                               String primaryBaseUrl,
                                               String primaryApiKey,
                                               Integer primaryMaxTokens,
                                               Double primaryTemperature,
                                               boolean logRequests,
                                               boolean logResponses,
                                               Consumer<FallbackStreamingChatModel.FallbackEvent> onFallback) {

        if (!shouldWrap()) {
            return primary;
        }

        List<FallbackStreamingChatModel.ModelCandidate> candidates = new ArrayList<>();
        candidates.add(new FallbackStreamingChatModel.ModelCandidate(primary, primaryDisplayName));

        for (ModelFallbackProperties.Candidate candidate : fallbackProperties.getCandidates()) {
            if (!candidate.isUsable()) {
                log.warn("跳过配置不完整的候选模型（缺 model-name）：label={}", candidate.getLabel());
                continue;
            }
            StreamingChatModel model = buildCandidateModel(
                    candidate, primaryBaseUrl, primaryApiKey, primaryMaxTokens,
                    primaryTemperature, logRequests, logResponses);
            candidates.add(new FallbackStreamingChatModel.ModelCandidate(
                    model, candidate.displayName()));
        }

        // 只有主模型时包装没有意义，直接返回原对象 ——
        // 少一层包装就少一份排查时的迷惑
        if (candidates.size() <= 1) {
            log.info("候选模型均不可用，{} 不启用降级", primaryDisplayName);
            return primary;
        }

        log.info("{} 已装配降级链：{}", primaryDisplayName,
                candidates.stream().map(FallbackStreamingChatModel.ModelCandidate::displayName).toList());

        return new FallbackStreamingChatModel(
                candidates, fallbackProperties.getAttemptsPerCandidate(), onFallback);
    }

    /**
     * 是否需要包装。任意条件不满足都返回 false，保持与改造前完全一致的行为。
     */
    private boolean shouldWrap() {
        return fallbackProperties.isEnabled()
                && fallbackProperties.getCandidates() != null
                && !fallbackProperties.getCandidates().isEmpty();
    }

    /**
     * 构造单个候选模型，未覆盖的字段沿用主模型配置。
     */
    private StreamingChatModel buildCandidateModel(ModelFallbackProperties.Candidate candidate,
                                                   String primaryBaseUrl,
                                                   String primaryApiKey,
                                                   Integer primaryMaxTokens,
                                                   Double primaryTemperature,
                                                   boolean logRequests,
                                                   boolean logResponses) {
        String baseUrl = firstNonBlank(candidate.getBaseUrl(), primaryBaseUrl);
        String apiKey = firstNonBlank(candidate.getApiKey(), primaryApiKey);
        int maxTokens = (candidate.getMaxTokens() != null && candidate.getMaxTokens() > 0)
                ? candidate.getMaxTokens() : primaryMaxTokens;
        // 温度这类采样参数不单独配 —— 降级目标是「尽量像主模型」，
        // 换采样参数会让输出风格突变，反而更难排查
        double temperature = primaryTemperature != null ? primaryTemperature : 0.7;

        log.debug("构造候选模型：label={}, modelName={}, baseUrl={}, maxTokens={}, 使用独立 apiKey={}",
                candidate.displayName(), candidate.getModelName(), baseUrl, maxTokens,
                candidate.getApiKey() != null && !candidate.getApiKey().isBlank());

        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(candidate.getModelName())
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return (preferred != null && !preferred.isBlank()) ? preferred : fallback;
    }
}
