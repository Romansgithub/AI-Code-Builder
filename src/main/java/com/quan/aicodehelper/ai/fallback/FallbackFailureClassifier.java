package com.quan.aicodehelper.ai.fallback;

import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 判断一次模型调用失败是否值得降级到备用模型。只对「换个模型或换个 key 可能就好了」的失败降级：
 * 内容被安全策略拦截、参数非法、上下文超长换谁结果都一样，重试只多烧一次调用，与自愈里「只重试代码问题」同理。
 * 不能用 LangChain4j 自带的 Retriable / NonRetriable：它回答的是「重试同一个模型有意义吗」，
 * 而这里要问的是「换模型或换 key 有意义吗」，{@link AuthenticationException} 是最该降级的一类。
 */
@Slf4j
public final class FallbackFailureClassifier {

    private FallbackFailureClassifier() {
    }

    /**
     * 该失败是否应触发降级。
     * @return true 表示换备用模型或换 key 有希望成功
     */
    public static boolean shouldFallback(Throwable error) {
        if (error == null) {
            return false;
        }

        // 业务语义失败：换模型结果一样，不降级
        if (isSemanticFailure(error)) {
            return false;
        }

        // 基础设施 / 凭证 / 模型可用性失败：值得换候选试
        return isCandidateSwitchableFailure(error);
    }

    /** 业务语义失败：与模型服务本身无关，换谁都一样，不该降级。 */
    private static boolean isSemanticFailure(Throwable error) {
        Throwable cause = rootCause(error);
        // InvalidRequestException 覆盖参数格式错、上下文超长等，是调用方的问题，备用模型同样处理不了
        return cause instanceof InvalidRequestException
                || cause instanceof IllegalArgumentException;
    }

    /** 换候选（另一个模型或另一套 key）有希望成功的失败。 */
    private static boolean isCandidateSwitchableFailure(Throwable error) {
        Throwable cause = rootCause(error);

        // 1. 限流：换模型或换 key 都能绕开
        if (cause instanceof RateLimitException) {
            return true;
        }
        // 2. 鉴权失败 / 额度耗尽：换 key 就是解法（它继承 NonRetriableException，但那是「重试同模型」的结论）
        if (cause instanceof AuthenticationException) {
            return true;
        }
        // 3. 模型不存在：当前模型名可能有误，换候选可能就对了
        if (cause instanceof ModelNotFoundException) {
            return true;
        }
        // 4. 服务端 5xx / 超时：典型的「换个端点也许能通」
        if (cause instanceof InternalServerException || cause instanceof TimeoutException) {
            return true;
        }

        // 5. HTTP 层兜底：429 限流、5xx 服务端问题；401/403 也归入可降级（额度耗尽常如此返回）
        if (cause instanceof HttpException http) {
            int code = http.statusCode();
            return code == 429 || code == 401 || code == 403 || code == 404 || code >= 500;
        }

        // 6. 网络层：连不上、DNS 失败、读超时，换个端点也许能通
        if (cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof SocketTimeoutException) {
            return true;
        }

        // 7. IOException 往往是连接被重置之类的传输层问题
        if (cause instanceof IOException) {
            return true;
        }

        // 8. 其余未知异常不降级：宁可如实暴露错误，也不因「看不懂」多打一次调用；
        //    新增的异常类型默认也不降级，需要显式确认。
        log.debug("未识别的失败类型，不触发降级：{}", cause.getClass().getName());
        return false;
    }

    /** 取最根部 cause：LangChain4j 与 Reactor 都会包装异常，只看最外层会误判。 */
    public static Throwable rootCause(Throwable error) {
        Throwable current = error;
        // 限制深度，避免异常链自引用时死循环
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth++ < 20) {
            current = current.getCause();
        }
        return current;
    }

    /** 给用户看的简短失败说明。 */
    public static String describe(Throwable error) {
        Throwable cause = rootCause(error);
        if (cause instanceof RateLimitException) {
            return "模型服务限流";
        }
        if (cause instanceof TimeoutException || cause instanceof SocketTimeoutException) {
            return "模型服务超时";
        }
        if (cause instanceof AuthenticationException) {
            return "模型鉴权失败（可能额度耗尽或密钥失效）";
        }
        if (cause instanceof ModelNotFoundException) {
            return "模型不存在";
        }
        if (cause instanceof HttpException http) {
            return "模型服务返回 HTTP " + http.statusCode();
        }
        if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
            return "无法连接模型服务";
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank()) ? cause.getClass().getSimpleName() : message;
    }
}
