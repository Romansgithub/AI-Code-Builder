package com.quan.aicodehelper.ai.fallback;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 带降级能力的 {@link StreamingChatModel} 装饰器。
 *
 * <p>按顺序尝试候选模型，当前一个失败且失败类型值得降级时切到下一个。
 * 主模型排在候选队列的第 0 位，逻辑上与其他候选完全一致 ——
 * 「主模型」与「备用模型」的区别只是顺序，不需要两套代码路径。</p>
 *
 * <h3>关键约束：一旦开始输出就不能再降级</h3>
 *
 * <p>这是整个实现里最容易写错的地方。流式响应是<b>不可回滚</b>的：
 * 如果主模型已经吐了 500 个 token 之后才失败，再切到备用模型重新生成，
 * 用户拿到的会是「前 500 token + 备用模型的完整输出」这种拼接乱码 ——
 * 比直接报错更糟，因为它是静默的，用户会以为生成成功了。</p>
 *
 * <p>因此规则是：<b>只在「尚未收到任何 partial response」时才能降级</b>。
 * 一旦有 token 流出，后续任何失败都直接上报，不再尝试备用模型。
 * 这与 HTTP 的语义也一致 —— 响应头已发出就无法再改状态码。</p>
 *
 * <h3>为什么不在工具调用中途降级</h3>
 *
 * <p>同理：如果主模型已经执行了写文件工具，备用模型从头再来会重复写入，
 * 且对话记忆里会留下两套不一致的上下文。工具调用类请求的失败一律不降级。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class FallbackStreamingChatModel implements StreamingChatModel {

    /**
     * 候选模型队列，第 0 位是主模型。
     */
    private final List<ModelCandidate> candidates;

    /**
     * 每个候选的最大尝试次数（1 表示不重试，直接切下一个）。
     */
    private final int attemptsPerCandidate;

    /**
     * 降级发生时的回调，供上层推送进度、记录指标。
     * 参数为「失败原因描述」与「切换到的模型展示名」。
     */
    private final Consumer<FallbackEvent> onFallback;

    /**
     * 一个候选模型及其展示名。
     */
    public record ModelCandidate(StreamingChatModel model, String displayName) {
    }

    /**
     * 降级事件，用于日志、进度推送与指标。
     */
    public record FallbackEvent(String fromModel, String toModel, String reason, int attemptIndex) {
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        if (candidates.isEmpty()) {
            handler.onError(new IllegalStateException("没有可用的模型候选"));
            return;
        }
        dispatch(chatRequest, handler, 0, 0, null);
    }

    /**
     * 尝试第 {@code index} 个候选（其第 {@code attempt} 次尝试）。
     *
     * @param previousError 上一个候选的失败原因，用于日志与降级事件；首个候选为 null
     */
    private void dispatch(ChatRequest chatRequest,
                          StreamingChatResponseHandler handler,
                          int index,
                          int attempt,
                          Throwable previousError) {

        ModelCandidate candidate = candidates.get(index);

        // 每个候选独占一个「是否已产出」标志：跨候选复用会导致
        // 「主模型吐了 token → 备用模型误以为也不能降级」的错误判断
        AtomicBoolean produced = new AtomicBoolean(false);

        StreamingChatResponseHandler wrapped = new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                produced.set(true);
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                handleFailure(chatRequest, handler, index, attempt, candidate, produced, error);
            }
        };

        try {
            candidate.model().chat(chatRequest, wrapped);
        } catch (Throwable error) {
            // 有些实现会同步抛而不是走 onError，两条路径都要接住
            handleFailure(chatRequest, handler, index, attempt, candidate, produced, error);
        }
    }

    /**
     * 统一处理一个候选的失败：决定是原地重试、切下一个、还是如实上报。
     */
    private void handleFailure(ChatRequest chatRequest,
                              StreamingChatResponseHandler handler,
                              int index,
                              int attempt,
                              ModelCandidate candidate,
                              AtomicBoolean produced,
                              Throwable error) {

        // 已经输出过内容 —— 不能再降级，否则会产生拼接乱码。
        // 这是本类最重要的一条判断，见类注释。
        if (produced.get()) {
            log.warn("模型 {} 在已输出部分内容后失败，不再降级（避免拼接乱码）：{}",
                    candidate.displayName(), FallbackFailureClassifier.describe(error));
            handler.onError(error);
            return;
        }

        boolean worthFallback = FallbackFailureClassifier.shouldFallback(error);
        String reason = FallbackFailureClassifier.describe(error);

        if (!worthFallback) {
            log.warn("模型 {} 失败且不值得降级（{}），如实上报",
                    candidate.displayName(), reason);
            handler.onError(error);
            return;
        }

        // 同一候选还有剩余尝试次数：原地重试
        if (attempt + 1 < attemptsPerCandidate) {
            log.warn("模型 {} 第 {}/{} 次尝试失败（{}），原地重试",
                    candidate.displayName(), attempt + 1, attemptsPerCandidate, reason);
            dispatch(chatRequest, handler, index, attempt + 1, error);
            return;
        }

        int nextIndex = index + 1;
        if (nextIndex >= candidates.size()) {
            log.error("模型 {} 失败（{}），且已无更多候选可用，共尝试 {} 个模型",
                    candidate.displayName(), reason, candidates.size());
            handler.onError(error);
            return;
        }

        ModelCandidate next = candidates.get(nextIndex);
        log.warn("模型 {} 失败（{}），降级到 {}（第 {}/{} 个候选）",
                candidate.displayName(), reason, next.displayName(), nextIndex + 1, candidates.size());

        if (onFallback != null) {
            onFallback.accept(new FallbackEvent(
                    candidate.displayName(), next.displayName(), reason, nextIndex));
        }

        dispatch(chatRequest, handler, nextIndex, 0, error);
    }
}
