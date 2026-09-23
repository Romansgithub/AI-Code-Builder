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
 * 启动时自检 embedding 接口是否真的可用，把「静默降级」变成一句明确结论。
 *
 * <p><b>它解决什么问题</b>：RAG 的降级设计是「任何一环失败都不中断生成」——
 * 这个设计本身是对的，但它有个副作用：<b>配置写错时系统照样跑，只是悄悄变笨</b>。
 * 具体表现是检索从「向量 × 0.7 + 关键词 × 0.3」退化成纯关键词，
 * 召回质量下降一大截，而日志里只有一条 INFO 级的
 * 「向量不可用，已退化为关键词检索」，混在长长的生成日志里几乎必然漏掉。</p>
 *
 * <p>最容易踩的是「环境变量没注入」这一类：Spring 绑定 {@code @ConfigurationProperties}
 * 时占位符解析器带 {@code ignoreUnresolvablePlaceholders=true}，缺失的环境变量
 * 会被原样保留成 {@code ${LLM_BASE_URL}} 字符串 —— 配置读起来是齐的，
 * 请求却打到不存在的地址上。典型场景是环境变量只配在 IDE 运行配置里，
 * 命令行启动时就没有。这种情况靠读配置是查不出来的，只能真发一次请求。</p>
 *
 * <p><b>为什么用 {@link ApplicationReadyEvent} 加独立线程</b>：与
 * {@link LocalServiceChecker} 同样的理由 —— 探测有网络往返，放在
 * {@code @PostConstruct} 里等于给启动流程埋一个不确定的卡顿点；
 * 而 {@code ApplicationReadyEvent} 是同步派发的，在这一层阻塞会推迟
 * 「应用已就绪」的信号。独立守护线程既让日志清晰，也不干扰启动时序。</p>
 *
 * <p><b>为什么只报结论、不阻止启动</b>：embedding 不可用只影响检索质量，
 * 生成链路本身照常工作。为一个可选能力的配置问题把整个应用拦在启动阶段，
 * 代价远大于收益 —— 所以这里只给 WARN，并且<b>失败路径绝不抛出</b>。</p>
 *
 * <p><b>为什么默认开启</b>：代价是一条十几 token 的请求。用它换「填完 key
 * 立刻知道通没通」，比事后从检索结果反推便宜得多。不打算用向量检索的可以
 * 设 {@code app.rag.embedding.self-check=false} 关掉。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEmbeddingChecker {

    private final RagProperties ragProperties;

    private final EmbeddingClient embeddingClient;

    /**
     * 探测线程。持有引用是为了在应用关闭时能及时收尾，避免留下游离线程。
     */
    private volatile Thread probeThread;

    /**
     * 关闭时中断探测：应用都要关了，没必要再等一次不会复活的网络往返。
     */
    @PreDestroy
    public void shutdown() {
        Thread t = this.probeThread;
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
    }

    /**
     * 应用就绪后触发自检。
     */
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

    /**
     * 自检是否生效。
     */
    boolean selfCheckActive() {
        return ragProperties.isEnabled()
                && ragProperties.getEmbedding() != null
                && ragProperties.getEmbedding().isSelfCheck();
    }

    /**
     * 执行自检并给出结论。纯计算、不写日志，便于直接断言 —— 日志只由
     * {@link #report(CheckResult)} 负责，两者分开后测试不必去捕获日志输出。
     */
    CheckResult check() {
        if (!ragProperties.isEnabled()) {
            return CheckResult.skipped("RAG 总开关关闭");
        }
        if (!selfCheckActive()) {
            return CheckResult.skipped("app.rag.embedding.self-check=false");
        }
        // 配置问题在本地就能判定，不发请求 —— 省一次注定失败的往返，
        // 也避免把「没配好」这种问题混进「网络不通」的排查里
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

    /**
     * 配置没填好的提示。这类问题的特点是「系统照常跑」，所以必须点明它不会自己暴露。
     */
    private static final String CONFIG_HINT =
            "embedding 不可用不会让生成失败，只会让检索退化为纯关键词，"
                    + "所以这个问题不会自己冒出来，必须靠这项检查发现。\n"
                    + "      若确实不打算用向量检索，可设 app.rag.embedding.self-check=false 关掉本项。";

    /**
     * 预算耗尽的提示。
     *
     * <p><b>为什么这一条必须单独写</b>：它长得最像一个「掉线」或「key 失效」，
     * 于是最容易被误诊。实际上后端好好的、key 也是好的，只是本项自检自己
     * 也要占一次 LLM 调用额度 —— 额度用完后它当然也会被拒。
     * 若沿用上面那段通用提示，等于让人去查一个根本不存在的问题。</p>
     */
    private static final String BUDGET_HINT =
            "这一条与 key、与模型都无关，别去换 key：本项自检同样要占一次 LLM 调用额度，"
                    + "额度用完后它自己也会被拒绝。\n"
                    + "      调大 app.budget.max-calls 后重启可续上额度；"
                    + "若不打算用向量检索，可设 app.rag.embedding.self-check=false 关掉本项。";

    /**
     * 调用失败的提示。配置是对的，所以方向在网络与服务商那一侧。
     */
    private static final String CALL_HINT =
            "配置本身没问题，按上面的方向查网络或服务商侧；"
                    + "embedding 不可用不会让生成失败，只会让检索退化为纯关键词。\n"
                    + "      若确实不打算用向量检索，可设 app.rag.embedding.self-check=false 关掉本项。";

    /**
     * 输出结论。通过时一行 INFO 带过；未通过时聚成一块并给出可执行的建议 ——
     * 提示的价值全在「下一步该做什么」上，只说「embedding 失败」等于把排查
     * 工作又丢回给用户。
     *
     * <p>提示文本由 {@link CheckResult#hint()} 携带而不是在这里写死：
     * 结论与建议本来就该一起产生，分开写迟早出现「结论改了口径、
     * 建议还指着旧方向」。</p>
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
     * 把异常翻译成「下一步该做什么」。
     *
     * <p>按 langchain4j 的异常体系分类，而不是匹配异常消息文本 ——
     * 消息由服务商决定，换个服务商（甚至换个语言）就会失效，
     * 而异常类型是 SDK 的对外契约。</p>
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

    /**
     * 端点主机名。只取 host 而不打完整地址：一是日志短，
     * 二是地址里偶尔被人塞进带凭证的查询串，不该进日志。
     */
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
     * 自检结论。
     *
     * <p>用 record 而不是布尔值，是为了区分「跳过」与「通过」——
     * 只用一个布尔值的话，检查没跑和检查通过会混为一谈，
     * 汇总时就分不清该不该提示。</p>
     *
     * <p>{@code hint} 与 {@code reason} 分开：{@code reason} 是「发生了什么」
     * （原样透出，供排查），{@code hint} 是「你现在该做什么」。
     * 三种失败的处置方式各不相同，所以建议必须跟着结论一起产生。</p>
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
