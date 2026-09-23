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
 * <p>单独抽类而非在 Config 里拼：构造候选要读配置、判断字段覆盖、处理非法值，逻辑量超出 Bean 定义；
 * 且三种生成模型各要一份候选链，抽出来才不必复制粘贴。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FallbackModelProvider {

    private final ModelFallbackProperties fallbackProperties;

    /**
     * 组装带降级的流式模型，候选未覆盖的字段沿用主模型配置。
     * @param onFallback 降级回调
     * @return 未配置候选时原样返回主模型，不引入额外包装
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

        // 只有主模型时包装没有意义，直接返回原对象。
        if (candidates.size() <= 1) {
            log.info("候选模型均不可用，{} 不启用降级", primaryDisplayName);
            return primary;
        }

        log.info("{} 已装配降级链：{}", primaryDisplayName,
                candidates.stream().map(FallbackStreamingChatModel.ModelCandidate::displayName).toList());

        return new FallbackStreamingChatModel(
                candidates, fallbackProperties.getAttemptsPerCandidate(), onFallback);
    }

    /** 是否需要包装；任意条件不满足都返回 false。 */
    private boolean shouldWrap() {
        return fallbackProperties.isEnabled()
                && fallbackProperties.getCandidates() != null
                && !fallbackProperties.getCandidates().isEmpty();
    }

    /** 构造单个候选模型，未覆盖的字段沿用主模型配置。 */
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
        // 采样参数不单独配：降级目标是「尽量像主模型」，换 temperature 会让输出风格突变，更难排查
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
