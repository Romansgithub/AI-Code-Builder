package com.quan.aicodehelper.ai.fallback;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型降级装饰器测试。
 *
 * <p>用一个可控的假模型替代真实模型：指定它在第几次调用时失败、失败前是否
 * 先吐几个 token。这样才能覆盖「已输出一半再失败」这种真实但难复现的场景。</p>
 */
@DisplayName("降级流式模型")
class FallbackStreamingChatModelTest {

    /**
     * 可控的假模型。
     */
    private static class FakeModel implements StreamingChatModel {

        private final String name;
        /** 每次被调用时依次取出的行为脚本 */
        private final List<Behavior> behaviors;
        private final AtomicInteger callCount = new AtomicInteger(0);
        private final List<String> receivedRequests = new ArrayList<>();

        FakeModel(String name, Behavior... behaviors) {
            this.name = name;
            this.behaviors = List.of(behaviors);
        }

        @Override
        public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            receivedRequests.add(String.valueOf(chatRequest.messages().size()));
            int index = callCount.getAndIncrement();
            Behavior behavior = behaviors.get(Math.min(index, behaviors.size() - 1));

            if (behavior.partialTokens > 0) {
                for (int i = 0; i < behavior.partialTokens; i++) {
                    handler.onPartialResponse(name + "-token" + i);
                }
            }
            if (behavior.error != null) {
                handler.onError(behavior.error);
            } else {
                handler.onCompleteResponse(
                        ChatResponse.builder().aiMessage(AiMessage.from(name + "-done")).build());
            }
        }

        int callCount() {
            return callCount.get();
        }
    }

    /**
     * 一次调用的行为：先吐 N 个 token，再成功或失败。
     */
    private record Behavior(int partialTokens, Throwable error) {
        static Behavior success() {
            return new Behavior(0, null);
        }

        static Behavior successWithTokens(int n) {
            return new Behavior(n, null);
        }

        static Behavior failWith(Throwable error) {
            return new Behavior(0, error);
        }

        static Behavior failAfterTokens(int n, Throwable error) {
            return new Behavior(n, error);
        }
    }

    /**
     * 收集结果的 handler。
     */
    private static class CollectingHandler implements StreamingChatResponseHandler {
        final List<String> tokens = new ArrayList<>();
        ChatResponse response;
        Throwable error;

        @Override
        public void onPartialResponse(String partialResponse) {
            tokens.add(partialResponse);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            this.response = completeResponse;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        boolean succeeded() {
            return response != null;
        }
    }

    private static ChatRequest anyRequest() {
        return ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("hi")).build();
    }

    @Nested
    @DisplayName("正常与直接成功")
    class HappyPath {

        @Test
        @DisplayName("主模型成功时不接触备用模型")
        void primarySucceeds() {
            FakeModel primary = new FakeModel("primary", Behavior.successWithTokens(2));
            FakeModel backup = new FakeModel("backup", Behavior.success());
            List<FallbackStreamingChatModel.FallbackEvent> events = new ArrayList<>();

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(candidate(primary), candidate(backup)), 1, events::add);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isTrue();
            assertThat(handler.tokens).containsExactly("primary-token0", "primary-token1");
            assertThat(backup.callCount()).isZero();
            assertThat(events).isEmpty();
        }
    }

    @Nested
    @DisplayName("降级触发")
    class Fallback {

        @Test
        @DisplayName("主模型限流时切到备用模型")
        void shouldFallbackOnRateLimit() {
            FakeModel primary = new FakeModel("primary", Behavior.failWith(new RateLimitException("429")));
            FakeModel backup = new FakeModel("backup", Behavior.successWithTokens(1));
            List<FallbackStreamingChatModel.FallbackEvent> events = new ArrayList<>();

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(candidate(primary), candidate(backup)), 1, events::add);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isTrue();
            assertThat(handler.tokens).containsExactly("backup-token0");
            assertThat(primary.callCount()).isEqualTo(1);
            assertThat(backup.callCount()).isEqualTo(1);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).fromModel()).isEqualTo("primary");
            assertThat(events.get(0).toModel()).isEqualTo("backup");
            assertThat(events.get(0).reason()).isEqualTo("模型服务限流");
        }

        @Test
        @DisplayName("主模型与第一个备用都失败时继续往下切")
        void shouldCascadeThroughCandidates() {
            FakeModel primary = new FakeModel("m1", Behavior.failWith(new RateLimitException("429")));
            FakeModel second = new FakeModel("m2", Behavior.failWith(new RateLimitException("429")));
            FakeModel third = new FakeModel("m3", Behavior.successWithTokens(1));

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(candidate(primary), candidate(second), candidate(third)), 1, null);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isTrue();
            assertThat(handler.tokens).containsExactly("m3-token0");
            assertThat(primary.callCount()).isEqualTo(1);
            assertThat(second.callCount()).isEqualTo(1);
            assertThat(third.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("所有候选都失败时如实上报最后一个错误")
        void shouldReportErrorWhenAllCandidatesFail() {
            FakeModel primary = new FakeModel("m1", Behavior.failWith(new RateLimitException("第一个失败")));
            FakeModel backup = new FakeModel("m2", Behavior.failWith(new RateLimitException("最后一个失败")));

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(candidate(primary), candidate(backup)), 1, null);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isFalse();
            assertThat(handler.error).hasMessage("最后一个失败");
        }

        @Test
        @DisplayName("同步抛异常（不走 onError）也能触发降级")
        void shouldHandleSynchronousThrow() {
            // 有些实现会同步抛而不是回调 onError，两条路径都要接住，
            // 否则降级在这种实现上会静默失效。
            // 注意 StreamingChatModel 有 default 方法、不是函数接口，只能用匿名类
            StreamingChatModel throwing = new StreamingChatModel() {
                @Override
                public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                    throw new RateLimitException("sync throw");
                }
            };
            FakeModel backup = new FakeModel("backup", Behavior.successWithTokens(1));

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(new FallbackStreamingChatModel.ModelCandidate(throwing, "thrower"),
                            candidate(backup)), 1, null);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isTrue();
            assertThat(handler.tokens).containsExactly("backup-token0");
        }
    }

    @Nested
    @DisplayName("不做降级的情况")
    class NoFallback {

        @Test
        @DisplayName("已输出部分内容后失败，绝不降级 —— 否则产生拼接乱码")
        void shouldNotFallbackAfterPartialOutput() {
            // 这是本组最重要的一条。流式响应不可回滚：若主模型吐了 2 个 token
            // 后失败再切备用模型重新生成，用户拿到的是「主模型前 2 token +
            // 备用模型完整输出」，比直接报错更糟 —— 它是静默的。
            FakeModel primary = new FakeModel("primary",
                    Behavior.failAfterTokens(2, new RateLimitException("mid-stream failure")));
            FakeModel backup = new FakeModel("backup", Behavior.successWithTokens(1));

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(candidate(primary), candidate(backup)), 1, null);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isFalse();
            assertThat(handler.error).isInstanceOf(RateLimitException.class);
            assertThat(handler.tokens).containsExactly("primary-token0", "primary-token1");
            assertThat(backup.callCount()).isZero();
        }

        @Test
        @DisplayName("业务语义失败不降级 —— 换模型结果一样，白烧一次调用")
        void shouldNotFallbackOnSemanticFailure() {
            FakeModel primary = new FakeModel("primary",
                    Behavior.failWith(new InvalidRequestException("context too long")));
            FakeModel backup = new FakeModel("backup", Behavior.successWithTokens(1));

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(candidate(primary), candidate(backup)), 1, null);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isFalse();
            assertThat(backup.callCount()).isZero();
            assertThat(handler.error).isInstanceOf(InvalidRequestException.class);
        }

        @Test
        @DisplayName("候选链为空时直接报错，不静默挂起")
        void emptyCandidatesShouldError() {
            FallbackStreamingChatModel model = new FallbackStreamingChatModel(List.of(), 1, null);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isFalse();
            assertThat(handler.error).hasMessageContaining("没有可用的模型候选");
        }
    }

    @Nested
    @DisplayName("同候选重试")
    class PerCandidateRetry {

        @Test
        @DisplayName("attempts=2 时同一模型会重试一次再切下一个")
        void shouldRetrySameCandidateThenFallback() {
            FakeModel primary = new FakeModel("primary",
                    Behavior.failWith(new RateLimitException("第一次")),
                    Behavior.failWith(new RateLimitException("第二次")));
            FakeModel backup = new FakeModel("backup", Behavior.successWithTokens(1));

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(candidate(primary), candidate(backup)), 2, null);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isTrue();
            assertThat(primary.callCount()).isEqualTo(2);
            assertThat(backup.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("attempts=2 时第一次失败后重试成功，不切备用")
        void shouldRecoverOnRetryWithoutFallback() {
            FakeModel primary = new FakeModel("primary",
                    Behavior.failWith(new RateLimitException("偶发抖动")),
                    Behavior.successWithTokens(1));
            FakeModel backup = new FakeModel("backup", Behavior.successWithTokens(1));

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(candidate(primary), candidate(backup)), 2, null);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isTrue();
            assertThat(handler.tokens).containsExactly("primary-token0");
            assertThat(backup.callCount()).isZero();
        }

        @Test
        @DisplayName("重试次数不因「已输出」标志在候选间串味而误判")
        void producedFlagShouldNotLeakAcrossCandidates() {
            // 每个候选独占「是否已输出」标志。若跨候选复用，
            // 主模型吐过 token 后会让备用模型误以为「也不能降级」
            FakeModel primary = new FakeModel("primary",
                    Behavior.successWithTokens(1), Behavior.success());
            FakeModel backup = new FakeModel("backup", Behavior.success());

            FallbackStreamingChatModel model = new FallbackStreamingChatModel(
                    List.of(candidate(primary), candidate(backup)), 1, null);

            CollectingHandler handler = new CollectingHandler();
            model.chat(anyRequest(), handler);

            assertThat(handler.succeeded()).isTrue();
            assertThat(backup.callCount()).isZero();
        }
    }

    private static FallbackStreamingChatModel.ModelCandidate candidate(FakeModel model) {
        return new FallbackStreamingChatModel.ModelCandidate(model, model.name);
    }
}
