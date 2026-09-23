package com.quan.aicodehelper.core.startup;

import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.core.rag.EmbeddingClient;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnresolvedModelServerException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * 启动时自检 embedding 接口是否真的可用。配置写错时（如环境变量未注入）系统照样跑，只是悄悄变笨。
 *
 * <p>用独立守护线程而非 {@code @PostConstruct}：在同步派发的 {@link ApplicationReadyEvent} 里
 * 做网络探测会推迟「应用已就绪」信号。不可用只影响检索质量，故只给 WARN、绝不抛出，也不阻止启动。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEmbeddingChecker {

    private final RagProperties ragProperties;

    private final EmbeddingClient embeddingClient;

    /** 持有引用是为了在应用关闭时能及时收尾，避免留下游离线程。 */
    private volatile Thread probeThread;

    @PreDestroy
    public void shutdown() {
        Thread t = this.probeThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!selfCheckActive()) {
            // 两种关闭原因都在这里解释一次，免得与 check() 内的判断重复维护
            log.info("RAG embedding 自检未执行（{}）",
                    ragProperties.isEnabled() ? "app.rag.embedding.self-check=false"
                            : "app.rag.enabled=false");
            return;
        }
        Thread t = new Thread(() -> report(check()), "rag-embedding-checker");
        t.setDaemon(true);
        this.probeThread = t;
        t.start();
    }

    boolean selfCheckActive() {
        return ragProperties.isEnabled()
                && ragProperties.getEmbedding() != null
                && ragProperties.getEmbedding().isSelfCheck();
    }

    /**
     * 执行自检并给出结论，纯计算、不写日志（日志只由 {@link #report(CheckResult)} 负责），便于直接断言。
     */
    CheckResult check() {
        if (!ragProperties.isEnabled()) {
            return CheckResult.skipped("RAG 总开关关闭");
        }
        if (!selfCheckActive()) {
            return CheckResult.skipped("app.rag.embedding.self-check=false");
        }
        // 配置问题本地就能判定，不发请求：省一次注定失败的往返，也不把「没配好」混进「网络不通」的排查
        String problem = embeddingClient.configProblem();
        if (problem != null) {
            return CheckResult.failed("配置不可用", problem, CONFIG_HINT);
        }
        EmbeddingClient.Probe probe = embeddingClient.probe();
        return switch (probe.kind()) {
            case OK -> CheckResult.ok(probe.dimension(), probe.costMs());
            case CONFIG_PROBLEM -> CheckResult.failed("配置不可用", probe.detail(), CONFIG_HINT);
            case BUDGET_BLOCKED -> CheckResult.failed("预算已耗尽", probe.detail(), BUDGET_HINT);
            case CALL_FAILURE -> CheckResult.failed("调用失败", describe(probe.error()), CALL_HINT);
        };
    }

    /** 配置没填好的提示。这类问题「系统照常跑」，不会自己暴露，必须点明。 */
    private static final String CONFIG_HINT =
            "embedding 不可用不会让生成失败，只会让检索退化为纯关键词，"
                    + "所以这个问题不会自己冒出来，必须靠这项检查发现。\n"
                    + "      若确实不打算用向量检索，可设 app.rag.embedding.self-check=false 关掉本项。";

    /**
     * 预算耗尽的提示。它长得最像「掉线」或「key 失效」，最容易被误诊：
     * 后端与 key 都好好的，只是本项自检自己也要占一次 LLM 调用额度。
     */
    private static final String BUDGET_HINT =
            "这一条与 key、与模型都无关，别去换 key：本项自检同样要占一次 LLM 调用额度，"
                    + "额度用完后它自己也会被拒绝。\n"
                    + "      调大 app.budget.max-calls 后重启可续上额度；"
                    + "若不打算用向量检索，可设 app.rag.embedding.self-check=false 关掉本项。";

    /** 调用失败的提示：配置本身没问题，方向在网络与服务商那一侧。 */
    private static final String CALL_HINT =
            "配置本身没问题，按上面的方向查网络或服务商侧；"
                    + "embedding 不可用不会让生成失败，只会让检索退化为纯关键词。\n"
                    + "      若确实不打算用向量检索，可设 app.rag.embedding.self-check=false 关掉本项。";

    /**
     * 输出结论。未通过时聚成一块并给出可执行的建议；提示文本由 {@link CheckResult#hint()} 携带
     * 而不是在这里写死，结论与建议一起产生，避免改了口径却还指着旧方向。
     */
    void report(CheckResult result) {
        if (result.skipped()) {
            return;
        }
        if (result.ok()) {
            log.info("[RAG 自检] embedding 就绪：模型={}，维度={}，往返 {}ms（端点 {}）",
                    embeddingClient.modelName(), result.dimension(), result.costMs(), endpoint());
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n┌─ RAG embedding 自检未通过 ──────────────────────────────\n");
        sb.append("│ [失败] ").append(result.title()).append("\n");
        sb.append("│        ").append(result.reason()).append("\n");
        sb.append("│        端点：").append(endpoint())
                .append("，模型：").append(embeddingClient.modelName()).append("\n");
        sb.append("└────────────────────────────────────────────────────────\n");
        sb.append("提示：").append(result.hint()).append("\n");
        log.warn(sb.toString());
    }

    /**
     * 把异常翻译成「下一步该做什么」。按异常类型而非异常消息分类：消息由服务商决定，
     * 换个服务商就会失效，异常类型才是 SDK 的对外契约。
     */
    static String describe(Throwable error) {
        if (error == null) {
            return "未知错误（无异常信息，请查看上方堆栈）";
        }
        if (error instanceof AuthenticationException) {
            return "服务端拒绝凭证（401/403）：api-key 无效，或该账号没有这个模型的调用权限";
        }
        if (error instanceof ModelNotFoundException) {
            return "模型不存在（404）：model-name 写错，或该模型未在服务商控制台开通";
        }
        if (error instanceof RateLimitException) {
            return "触发限流（429）：额度耗尽或请求过于频繁，可在服务商控制台确认余额";
        }
        if (error instanceof TimeoutException) {
            return "请求超时：网络不通，或需要走代理";
        }
        if (error instanceof UnresolvedModelServerException) {
            return "无法解析服务地址：base-url 写错或 DNS 不可达";
        }
        if (error instanceof HttpException http) {
            return "服务端返回 HTTP " + http.statusCode() + "：" + safeMessage(http);
        }
        return error.getClass().getSimpleName() + "：" + safeMessage(error);
    }

    /** 只取 host 而不打完整地址：一是日志短，二是地址里偶尔被人塞进带凭证的查询串。 */
    private String endpoint() {
        RagProperties.Embedding config = ragProperties.getEmbedding();
        if (config == null) {
            return "未配置";
        }
        String baseUrl = config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "未配置";
        }
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? "无法解析（" + baseUrl + "）" : host;
        } catch (Exception e) {
            // 形如 ${LLM_BASE_URL} 的未注入占位符走这里
            return baseUrl;
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "(无详细信息)" : message;
    }

    /**
     * 自检结论。用 record 而非布尔值，是为了区分「跳过」与「通过」；{@code reason} 是
     * 「发生了什么」，{@code hint} 是「你现在该做什么」，三种失败的处置各不相同。
     */
    record CheckResult(boolean skipped, boolean ok, String title, String reason, String hint,
                       int dimension, long costMs) {

        static CheckResult skipped(String why) {
            return new CheckResult(true, true, null, why, null, 0, 0L);
        }

        static CheckResult ok(int dimension, long costMs) {
            return new CheckResult(false, true, null, null, null, dimension, costMs);
        }

        static CheckResult failed(String title, String reason, String hint) {
            return new CheckResult(false, false, title, reason, hint, 0, 0L);
        }
    }
}
