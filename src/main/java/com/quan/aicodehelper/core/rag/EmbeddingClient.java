package com.quan.aicodehelper.core.rag;

import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.exception.BusinessException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量化客户端：把文本批量转成向量。
 *
 * <p><b>为什么自己分批而不交给 SDK 的自动分批</b>：
 * 预算闸门按「真实 HTTP 请求数」计费，而 SDK 内部分几批是它自己的实现细节 ——
 * 交给它分批，就只能按「调用了几次 embedAll」记账，实际请求数可能高一个数量级，
 * 预算面板上的数字就失去意义了。自己分批，才能让「记一次账」严格对应
 * 「发一个请求」。</p>
 *
 * <p><b>模型是懒加载的</b>：这里刻意不做成 Spring Bean。因为 RAG 允许在
 * 「没有配置 embedding 凭证」的情况下继续工作（只是不检索），
 * 而一个必须存在、否则启动失败的 Bean 会让这种降级变得不可能 ——
 * 为一个可选能力把整个应用拦在启动阶段，是不划算的。</p>
 */
@Slf4j
@Component
public class EmbeddingClient {

    @Resource
    private RagProperties ragProperties;

    /**
     * LLM 调用预算闸门。向量化同样会真实消耗额度，必须并入同一个账本 ——
     * 否则它就成了第五条「不受约束的烧钱路径」。
     */
    @Resource
    private LlmCallGuard llmCallGuard;

    /**
     * 启动自检用的探针文本。
     *
     * <p>刻意用「中文 + 英文标识符」的混合串：既能走到真实的分词路径，
     * 又能借返回维度验证服务商确实按配置的模型作答（个别服务商会静默回落到默认模型）。</p>
     */
    private static final String PROBE_TEXT = "embedding 自检 probe";

    /**
     * 自检在预算账本上的场景名。与索引路径分开命名，是为了在
     * 「额度怎么就用完了」这类排查里能一眼看出有一次是启动自检占的。
     */
    private static final String PROBE_SCENE = "RAG 自检";

    private volatile EmbeddingModel embeddingModel;

    /**
     * 未配置凭证的提示只打一次，避免每次检索都刷日志。
     */
    private volatile boolean missingConfigWarned = false;

    /**
     * 当前配置的 embedding 模型名，用于标记向量的「语义空间版本」。
     */
    public String modelName() {
        return ragProperties.getEmbedding() == null
                ? null
                : ragProperties.getEmbedding().getModelName();
    }

    /**
     * 向量化是否可用（开关打开且凭证齐备）。
     */
    public boolean available() {
        return ragProperties.isEnabled() && resolveModel() != null;
    }

    /**
     * 判断配置值是否是「没被替换掉的占位符」。
     *
     * <p><b>为什么单靠判空不够</b>：Spring 绑定 {@code @ConfigurationProperties} 时
     * 用的是 {@code PropertySourcesPlaceholdersResolver}，它内部的
     * {@code PropertyPlaceholderHelper} 构造参数里
     * {@code ignoreUnresolvablePlaceholders = true}，且未命中时
     * {@code resolvePlaceholder} 返回 {@code null} —— 于是环境变量缺失时，
     * {@code ${LLM_BASE_URL}} <b>既不报错也不变空，而是原样保留为这个字符串</b>。
     * 配置因此读起来「是齐的」（非空），真实请求却打到一个叫
     * {@code ${LLM_BASE_URL}} 的地址上。</p>
     *
     * <p>这正是「环境变量只配在 IDE 运行配置里」这类问题的表现形态：
     * 同一个项目在 IDE 里检索正常，在命令行跑就不检索，而日志里只有一条
     * 语焉不详的向量化失败。</p>
     */
    static boolean isUnresolvedPlaceholder(String value) {
        return value != null && value.startsWith("${") && value.endsWith("}");
    }

    /**
     * 配置体检：返回 {@code null} 表示可用，否则返回一句可直接展示给用户的原因。
     *
     * <p>把「判断配置好不好」收敛到这一个方法，是因为它有两个消费方 ——
     * 模型构建（{@link #resolveModel()}）和启动自检。两处各写一遍判断，
     * 迟早会漂移成「自检说没问题、实际不可用」。</p>
     */
    public String configProblem() {
        RagProperties.Embedding config = ragProperties.getEmbedding();
        if (config == null) {
            return "app.rag.embedding 整段未配置";
        }
        // 占位符必须排在判空之前：它是非空的，放后面就永远轮不到
        if (isUnresolvedPlaceholder(config.getBaseUrl())) {
            return "base-url 仍是未替换的占位符 " + config.getBaseUrl()
                    + " —— 环境变量没有注入到运行环境";
        }
        if (isUnresolvedPlaceholder(config.getApiKey())) {
            return "api-key 仍是未替换的占位符 " + config.getApiKey()
                    + " —— 环境变量没有注入到运行环境";
        }
        if (isUnresolvedPlaceholder(config.getModelName())) {
            return "model-name 仍是未替换的占位符 " + config.getModelName()
                    + " —— 环境变量没有注入到运行环境";
        }
        if (StrUtil.isBlank(config.getBaseUrl())) {
            return "base-url 为空";
        }
        if (StrUtil.isBlank(config.getApiKey())) {
            return "api-key 为空";
        }
        if (StrUtil.isBlank(config.getModelName())) {
            return "model-name 为空";
        }
        return null;
    }

    /**
     * 真实往返一次，供启动自检使用。不抛异常 —— 自检的价值是「给出结论」，
     * 异常本身就是结论的一部分。
     *
     * <p><b>它和索引路径共用同一个预算账本</b>：自检发的也是一次真实的
     * embedding 请求，凭什么不算钱？早先把它排除在外，理由只是「量很小、
     * 不由用户触发」，但那条理由同样适用于任何一次「就多一次而已」的调用 ——
     * 口子一旦开了，账目就不再是账目。现在它照常经过
     * {@link LlmCallGuard#acquire}，代价是启动时多记一次账。</p>
     *
     * <p><b>被闸门拒绝时单独成类，不能混进另外两种失败</b>：
     * 配置不可用是「改配置就好」，调用失败是「配置对但网络或服务商有问题」，
     * 而被拒绝是「额度用完了，压根没发出去」。三者混在一起，就会把
     * 额度问题误报成 key 无效 —— 那是一条让人白折腾半天的假线索。</p>
     */
    public Probe probe() {
        String problem = configProblem();
        if (problem != null) {
            return Probe.configProblem(problem);
        }
        try {
            EmbeddingModel model = resolveModel();
            if (model == null) {
                return Probe.configProblem("embedding 模型未能初始化，具体原因见上方告警");
            }
            // 记账在发请求之前：顺序反了就变成「先花钱再记账」
            llmCallGuard.acquire(PROBE_SCENE);
            long startedAt = System.nanoTime();
            Response<Embedding> response = model.embed(PROBE_TEXT);
            long costMs = (System.nanoTime() - startedAt) / 1_000_000L;
            return Probe.success(response.content().dimension(), costMs);
        } catch (BusinessException e) {
            // 必须是独立分支且排在 Exception 之前：它是闸门的拒绝信号，
            // 而不是服务端返回的错误
            return Probe.budgetBlocked(e.getMessage());
        } catch (Exception e) {
            return Probe.failure(e);
        }
    }

    /**
     * 自检探针的结局。
     *
     * <p>四种结局对应四种完全不同的下一步动作，所以用枚举显式列出，
     * 而不是用「是否成功 + 两个可空字段」去凑 —— 后者每多一种情况，
     * 调用方就要多猜一次「这个组合到底代表什么」。</p>
     */
    public record Probe(Kind kind, int dimension, long costMs, String detail, Throwable error) {

        /**
         * 结局类型。
         *
         * <ul>
         *   <li>{@link #OK} —— 通了，维度与耗时可用；</li>
         *   <li>{@link #CONFIG_PROBLEM} —— 配置没填好，请求根本没发；</li>
         *   <li>{@link #BUDGET_BLOCKED} —— 配置没问题，但预算闸门不让调；</li>
         *   <li>{@link #CALL_FAILURE} —— 请求发出去了，对方拒绝或网络不通。</li>
         * </ul>
         */
        public enum Kind {
            OK, CONFIG_PROBLEM, BUDGET_BLOCKED, CALL_FAILURE
        }

        public boolean ok() {
            return kind == Kind.OK;
        }

        static Probe success(int dimension, long costMs) {
            return new Probe(Kind.OK, dimension, costMs, null, null);
        }

        static Probe configProblem(String problem) {
            return new Probe(Kind.CONFIG_PROBLEM, 0, 0L, problem, null);
        }

        static Probe budgetBlocked(String reason) {
            return new Probe(Kind.BUDGET_BLOCKED, 0, 0L, reason, null);
        }

        static Probe failure(Throwable error) {
            return new Probe(Kind.CALL_FAILURE, 0, 0L, null, error);
        }
    }

    /**
     * 批量向量化。返回顺序与入参严格一致。
     *
     * <p>不吞异常：额度耗尽会抛业务异常、网络问题会抛运行时异常，
     * 两者对调用方的处置方式不同（前者应中止整轮索引，后者可以跳过单个文件），
     * 所以在这一层保持原样抛出，由调用方分类处理。</p>
     *
     * @param texts 待向量化的文本
     * @return 与入参等长的向量列表
     */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EmbeddingModel model = resolveModel();
        if (model == null) {
            throw new IllegalStateException("embedding 模型未配置，无法向量化");
        }
        int batchSize = Math.max(1, ragProperties.getEmbedding().getBatchSize());
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            List<TextSegment> batch = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                batch.add(TextSegment.from(texts.get(i)));
            }
            // 记在发请求之前：顺序反了就变成「先花钱再记账」，闸门失去意义
            llmCallGuard.acquire("RAG 向量化");
            Response<List<Embedding>> response = model.embedAll(batch);
            for (Embedding embedding : response.content()) {
                vectors.add(embedding.vector());
            }
        }
        if (vectors.size() != texts.size()) {
            // 数量对不上说明拼接会整体错位：第 N 个向量将不属于第 N 段文本。
            // 这种数据一旦入库，检索会静默返回「看起来合理但张冠李戴」的结果，
            // 比直接失败危险得多，所以在这里就断掉。
            throw new IllegalStateException(String.format(
                    "向量化结果数量与输入不一致：期望 %d，实际 %d", texts.size(), vectors.size()));
        }
        return vectors;
    }

    /**
     * 单条文本向量化，主要供查询侧使用。
     */
    public float[] embedOne(String text) {
        List<float[]> vectors = embedAll(List.of(text));
        return vectors.isEmpty() ? null : vectors.get(0);
    }

    private EmbeddingModel resolveModel() {
        EmbeddingModel local = embeddingModel;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (embeddingModel != null) {
                return embeddingModel;
            }
            String problem = configProblem();
            if (problem != null) {
                if (!missingConfigWarned) {
                    missingConfigWarned = true;
                    log.warn("RAG 已启用但 embedding 配置不可用（{}），"
                            + "向量检索将被跳过（仍可用关键词那一路）—— 其余功能不受影响",
                            problem);
                }
                return null;
            }
            RagProperties.Embedding config = ragProperties.getEmbedding();
            try {
                embeddingModel = OpenAiEmbeddingModel.builder()
                        .baseUrl(config.getBaseUrl())
                        .apiKey(config.getApiKey())
                        .modelName(config.getModelName())
                        .maxRetries(Math.max(0, config.getMaxRetries()))
                        .timeout(Duration.ofSeconds(60))
                        .build();
                log.info("RAG 向量化模型就绪：{}（批次 {} 条）",
                        config.getModelName(), config.getBatchSize());
                return embeddingModel;
            } catch (Exception e) {
                if (!missingConfigWarned) {
                    missingConfigWarned = true;
                    log.warn("RAG 向量化模型初始化失败，检索增强将自动跳过：{}", e.getMessage());
                }
                return null;
            }
        }
    }
}
