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
 * 判断一次模型调用失败是否值得降级到备用模型。
 *
 * <h3>核心原则：只对「换个模型 / 换个 key 可能就好了」的失败降级</h3>
 *
 * <p>无脑降级看起来更「稳」，实际有害：内容被安全策略拦截、请求参数非法、
 * 上下文超长这类问题，换任何模型都是同样结果 —— 重试只是多烧一次调用、
 * 多让用户等一轮，最后还是失败。这与构建自愈里「只对代码问题重试、
 * 环境问题不重试」是同一条原则：<b>重试之前先问「重试能让它变好吗」</b>。</p>
 *
 * <h3>与 LangChain4j 自带分类的关键区别（容易搞错的地方）</h3>
 *
 * <p>LangChain4j 有 {@code RetriableException} / {@code NonRetriableException}
 * 两族异常，看起来可以直接拿来用，但两者的语义<b>并不相同</b>：</p>
 *
 * <ul>
 *   <li>LangChain4j 的分类回答的是「<b>重试同一个模型</b>有意义吗」</li>
 *   <li>我们要回答的是「<b>换个模型或换个 key</b> 有意义吗」</li>
 * </ul>
 *
 * <p>这个差异在 {@link AuthenticationException} 上体现得最清楚：
 * 它继承 {@code NonRetriableException}（重试同一个 key 当然没用），
 * 但对我们而言恰恰是最该降级的场景之一 —— <b>换个 key 就解决了</b>。
 * 「额度耗尽」通常也以 401/403 的形式返回，正是开发者最常遇到的降级需求。</p>
 *
 * <p>{@link ModelNotFoundException} 同理：当前模型名写错了，
 * 换个候选模型完全可能正常。因此这里<b>不直接用</b> LangChain4j 的
 * Retriable/NonRetriable 判断，而是按自己的语义重新分类，
 * 只在缺少更精确信息的兜底分支上参考它。</p>
 */
@Slf4j
public final class FallbackFailureClassifier {

    private FallbackFailureClassifier() {
    }

    /**
     * 该失败是否应该触发降级。
     *
     * @param error 捕获到的异常
     * @return true 表示换备用模型 / 换 key 有希望成功
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

    /**
     * 业务语义失败 —— 与模型服务本身无关，换谁都一样，不该降级。
     */
    private static boolean isSemanticFailure(Throwable error) {
        Throwable cause = rootCause(error);
        // InvalidRequestException 覆盖参数格式错、上下文超长等：
        // 是调用方的问题，备用模型同样处理不了
        return cause instanceof InvalidRequestException
                || cause instanceof IllegalArgumentException;
    }

    /**
     * 换候选（另一个模型或另一套 key）有希望成功的失败。
     */
    private static boolean isCandidateSwitchableFailure(Throwable error) {
        Throwable cause = rootCause(error);

        // 1. 限流：换模型或换 key 都能绕开
        if (cause instanceof RateLimitException) {
            return true;
        }
        // 2. 鉴权失败 / 额度耗尽：换 key 就是解法（注意它继承 NonRetriableException，
        //    但不能因此就放弃 —— 那是对「重试同模型」而言的结论）
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

        // 5. HTTP 层兜底：429 限流、5xx 服务端问题。
        //    401/403 也归入可降级（额度耗尽常如此返回）
        if (cause instanceof HttpException http) {
            int code = http.statusCode();
            return code == 429 || code == 401 || code == 403 || code == 404 || code >= 500;
        }

        // 6. 网络层：连不上、DNS 失败、读超时 —— 换个端点也许能通
        if (cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof SocketTimeoutException) {
            return true;
        }

        // 7. IOException 往往是连接被重置之类的传输层问题
        if (cause instanceof IOException) {
            return true;
        }

        // 8. 其余未知异常不降级 —— 宁可让错误如实暴露，
        //    也不要因为「看不懂」就多打一次调用。
        //    这也意味着新增的异常类型默认是「不安全降级」，需要显式确认。
        log.debug("未识别的失败类型，不触发降级：{}", cause.getClass().getName());
        return false;
    }

    /**
     * 取到最根部的 cause。
     *
     * <p>LangChain4j 与 Reactor 都会包装异常，只看最外层会误判
     * （例如外层是 RuntimeException，里面才是 RateLimitException）。</p>
     */
    public static Throwable rootCause(Throwable error) {
        Throwable current = error;
        // 限制深度，避免异常链自引用时死循环
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth++ < 20) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * 给用户看的简短失败说明。
     */
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
