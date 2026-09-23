package com.quan.aicodehelper.ai.fallback;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 降级触发条件测试：固化「什么该降级、什么不该」的判断，防止后续被人改坏。
 */
@DisplayName("降级失败分类器")
class FallbackFailureClassifierTest {

    @Nested
    @DisplayName("应该降级的失败")
    class ShouldFallback {

        @Test
        @DisplayName("限流：换模型或换 key 都能绕开")
        void rateLimit() {
            assertThat(FallbackFailureClassifier.shouldFallback(new RateLimitException("429"))).isTrue();
        }

        @Test
        @DisplayName("服务端 5xx：典型的基础设施抖动")
        void internalServerError() {
            assertThat(FallbackFailureClassifier.shouldFallback(new InternalServerException("500"))).isTrue();
        }

        @Test
        @DisplayName("超时：换个端点也许能通")
        void timeout() {
            assertThat(FallbackFailureClassifier.shouldFallback(new TimeoutException("timeout"))).isTrue();
        }

        @Test
        @DisplayName("鉴权失败必须降级 —— 它继承 NonRetriableException，但不能因此放弃")
        void authentication() {
            // LangChain4j 把 AuthenticationException 归为 NonRetriable，但对「换 key」而言
            // 它最该降级（额度耗尽常这样返回），照搬分类会丢能力
            assertThat(FallbackFailureClassifier.shouldFallback(new AuthenticationException("401"))).isTrue();
        }

        @Test
        @DisplayName("模型不存在：当前模型名可能有误，换候选可能就对了")
        void modelNotFound() {
            assertThat(FallbackFailureClassifier.shouldFallback(new ModelNotFoundException("no such model"))).isTrue();
        }

        @Test
        @DisplayName("HTTP 429 / 401 / 403 / 404 / 5xx 均可降级")
        void httpStatusCodes() {
            assertThat(FallbackFailureClassifier.shouldFallback(new HttpException(429, "too many"))).isTrue();
            assertThat(FallbackFailureClassifier.shouldFallback(new HttpException(401, "unauthorized"))).isTrue();
            assertThat(FallbackFailureClassifier.shouldFallback(new HttpException(403, "forbidden"))).isTrue();
            assertThat(FallbackFailureClassifier.shouldFallback(new HttpException(404, "not found"))).isTrue();
            assertThat(FallbackFailureClassifier.shouldFallback(new HttpException(502, "bad gateway"))).isTrue();
            assertThat(FallbackFailureClassifier.shouldFallback(new HttpException(503, "unavailable"))).isTrue();
        }

        @Test
        @DisplayName("HTTP 400 不降级：是请求本身的问题")
        void httpBadRequestShouldNotFallback() {
            assertThat(FallbackFailureClassifier.shouldFallback(new HttpException(400, "bad request"))).isFalse();
        }

        @Test
        @DisplayName("网络层失败：连不上、DNS 失败、读超时")
        void networkFailures() {
            assertThat(FallbackFailureClassifier.shouldFallback(new ConnectException("refused"))).isTrue();
            assertThat(FallbackFailureClassifier.shouldFallback(new UnknownHostException("dns"))).isTrue();
            assertThat(FallbackFailureClassifier.shouldFallback(new SocketTimeoutException("read timeout"))).isTrue();
        }

        @Test
        @DisplayName("IOException 视为传输层问题")
        void ioException() {
            assertThat(FallbackFailureClassifier.shouldFallback(new IOException("connection reset"))).isTrue();
        }
    }

    @Nested
    @DisplayName("不该降级的失败")
    class ShouldNotFallback {

        @Test
        @DisplayName("请求参数非法：是调用方的问题，备用模型同样处理不了")
        void invalidRequest() {
            assertThat(FallbackFailureClassifier.shouldFallback(
                    new InvalidRequestException("context too long"))).isFalse();
        }

        @Test
        @DisplayName("IllegalArgumentException：参数构造错误")
        void illegalArgument() {
            assertThat(FallbackFailureClassifier.shouldFallback(
                    new IllegalArgumentException("bad param"))).isFalse();
        }

        @Test
        @DisplayName("未识别的异常默认不降级 —— 宁可如实暴露，也不盲目多打一次调用")
        void unknownExceptionShouldNotFallback() {
            assertThat(FallbackFailureClassifier.shouldFallback(
                    new IllegalStateException("something weird"))).isFalse();
        }

        @Test
        @DisplayName("null 不降级")
        void nullShouldNotFallback() {
            assertThat(FallbackFailureClassifier.shouldFallback(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("异常链解析")
    class CauseResolution {

        @Test
        @DisplayName("应穿透多层包装找到真实原因")
        void shouldUnwrapNestedCauses() {
            // Reactor 与 LangChain4j 都会包装异常，只看最外层会误判
            Throwable wrapped = new RuntimeException("outer",
                    new IllegalStateException("middle",
                            new RateLimitException("真实原因：限流")));

            assertThat(FallbackFailureClassifier.shouldFallback(wrapped)).isTrue();
            assertThat(FallbackFailureClassifier.describe(wrapped)).isEqualTo("模型服务限流");
        }

        @Test
        @DisplayName("包装层是业务语义失败时，即使外层像基础设施错误也不降级")
        void semanticFailureWinsEvenWhenWrapped() {
            // 真实原因决定结论，不能被外层类型带偏
            Throwable wrapped = new RuntimeException("outer",
                    new InvalidRequestException("真实原因：上下文超长"));

            assertThat(FallbackFailureClassifier.shouldFallback(wrapped)).isFalse();
        }

        @Test
        @DisplayName("自引用异常链不死循环")
        void selfReferencingCauseShouldNotLoop() {
            // 防御性：构造一个 cause 指向自己的异常（现实中可能出现）
            Throwable selfRef = new RuntimeException("self") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            assertThat(FallbackFailureClassifier.shouldFallback(selfRef)).isFalse();
        }
    }

    @Nested
    @DisplayName("失败原因描述")
    class Describe {

        @Test
        @DisplayName("各类失败都有可读描述，不暴露类名")
        void shouldDescribeReadably() {
            assertThat(FallbackFailureClassifier.describe(new RateLimitException("x"))).isEqualTo("模型服务限流");
            assertThat(FallbackFailureClassifier.describe(new TimeoutException("x"))).isEqualTo("模型服务超时");
            assertThat(FallbackFailureClassifier.describe(new AuthenticationException("x")))
                    .contains("额度耗尽");
            assertThat(FallbackFailureClassifier.describe(new ModelNotFoundException("x"))).isEqualTo("模型不存在");
            assertThat(FallbackFailureClassifier.describe(new HttpException(503, "x")))
                    .contains("503");
            assertThat(FallbackFailureClassifier.describe(new ConnectException("x")))
                    .isEqualTo("无法连接模型服务");
        }

        @Test
        @DisplayName("无消息的异常回退为类名，不会返回 null")
        void nullMessageShouldFallbackToClassName() {
            String described = FallbackFailureClassifier.describe(new IllegalStateException());

            assertThat(described).isNotBlank();
            assertThat(described).isEqualTo("IllegalStateException");
        }
    }
}
