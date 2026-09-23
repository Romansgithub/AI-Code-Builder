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
 * 向量化客户端：把文本批量转成向量。自己分批，以便预算闸门按真实 HTTP 请求数记账。
 */
@Slf4j
@Component
public class EmbeddingClient {

    @Resource
    private RagProperties ragProperties;

    /** LLM 调用预算闸门。向量化也真实消耗额度，必须并入同一账本，否则成不受约束的烧钱路径。 */
    @Resource
    private LlmCallGuard llmCallGuard;

    /** 启动自检探针文本。用「中文 + 英文标识符」混合串，既能走真实分词，又能借返回维度验证未静默回落到默认模型。 */
    private static final String PROBE_TEXT = "embedding 自检 probe";

    /** 自检在预算账本上的场景名，与索引路径分开便于排查「额度怎么用完」。 */
    private static final String PROBE_SCENE = "RAG 自检";

    private volatile EmbeddingModel embeddingModel;

    /** 未配置凭证的提示只打一次，避免每次检索都刷日志。 */
    private volatile boolean missingConfigWarned = false;

    /** 当前配置的 embedding 模型名，用于标记向量的「语义空间版本」。 */
    public String modelName() {
        return ragProperties.getEmbedding() == null
                ? null
                : ragProperties.getEmbedding().getModelName();
    }

    public boolean available() {
        return ragProperties.isEnabled() && resolveModel() != null;
    }

    /** 判断值是否仍是未替换的占位符：Spring 在环境变量缺失时会把 {@code ${...}} 原样保留而非变空，导致配置「看着齐」实则打到错误地址。 */
    static boolean isUnresolvedPlaceholder(String value) {
        return value != null && value.startsWith("${") && value.endsWith("}");
    }

    /** 配置体检：返回 null 表示可用，否则返回可直接展示给用户的原因。收敛到一处，避免模型构建与启动自检两处判断漂移。 */
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

    /** 真实往返一次，供启动自检。与索引共用同一预算账本；被闸门拒绝须单独区分，否则会误报成 key 无效。不抛异常。 */
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
            // 记账在发请求之前：顺序反了变成「先花钱再记账」，闸门失去意义
            llmCallGuard.acquire(PROBE_SCENE);
            long startedAt = System.nanoTime();
            Response<Embedding> response = model.embed(PROBE_TEXT);
            long costMs = (System.nanoTime() - startedAt) / 1_000_000L;
            return Probe.success(response.content().dimension(), costMs);
        } catch (BusinessException e) {
            // 必须是独立分支且排在 Exception 之前：它是闸门的拒绝信号，而不是服务端返回的错误
            return Probe.budgetBlocked(e.getMessage());
        } catch (Exception e) {
            return Probe.failure(e);
        }
    }

    /** 自检探针的结局。四种结局对应不同下一步动作，用枚举显式列出而非可空字段凑。 */
    public record Probe(Kind kind, int dimension, long costMs, String detail, Throwable error) {

        /** 结局类型：OK 通了、CONFIG_PROBLEM 配置未填好、BUDGET_BLOCKED 被闸门拦、CALL_FAILURE 请求发出但失败。 */
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
     * 批量向量化，返回顺序与入参严格一致且与入参等长；不吞异常（额度耗尽抛业务异常、网络抛运行时异常），由调用方分类处理。
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
            // 记账在发请求之前：顺序反了变成「先花钱再记账」，闸门失去意义
            llmCallGuard.acquire("RAG 向量化");
            Response<List<Embedding>> response = model.embedAll(batch);
            for (Embedding embedding : response.content()) {
                vectors.add(embedding.vector());
            }
        }
        if (vectors.size() != texts.size()) {
            // 数量对不上说明拼接整体错位，检索会静默返回张冠李戴的结果，故在此断掉
            throw new IllegalStateException(String.format(
                    "向量化结果数量与输入不一致：期望 %d，实际 %d", texts.size(), vectors.size()));
        }
        return vectors;
    }

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
