package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 项目代码检索（RAG）配置。索引源是磁盘上的工程代码，它是唯一真相。
 * 检索为混合模式：向量管语义相近，关键词管字面对得上，单靠向量常捞回语义相近但无关的片段。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /**
     * 总开关。关闭后不索引、不检索，行为与改造前一致。
     */
    private boolean enabled = true;

    /**
     * 每次注入上下文的最大片段数。与 {@code max-context-chars} 双重约束，字符数超了照样要截。
     */
    private int topK = 8;

    /**
     * 单个文件最多贡献几个片段，防止高相似度的小工具文件占满 Top-K、挤掉真正要改的文件。
     */
    private int maxChunksPerFile = 3;

    /**
     * 注入上下文的字符数硬上限。按字符而非 token 估：无需分词器、跨模型可比，
     * 中文与代码混合时约 1 字符 ≈ 0.5~1 token。
     */
    private int maxContextChars = 12000;

    /**
     * 向量余弦在最终得分中的权重。
     */
    private double vectorWeight = 0.7D;

    /**
     * 关键词命中率在最终得分中的权重。
     */
    private double keywordWeight = 0.3D;

    /**
     * 全局样式文件在向量分上的折扣系数；取值 ≥ 1 或 ≤ 0 一律按关闭处理。
     * <p>默认 1.0 即关闭：排后本身不产生漏召，而被折扣压下去的可能是正确答案。
     * 启用前须同时看排后清单与漏召清单，只看前者会把压掉正确文件当成成功。
     * 建议区间 0.85 ~ 0.95，详见 docs/design-notes.md 第五节。
     */
    private double genericFilePenalty = 1.0D;

    /**
     * 超过此大小的文件直接跳过索引。生成的大文件（打包产物、数据表等）既不该被检索，向量化也纯属浪费额度。
     */
    private long maxFileBytes = 200_000L;

    /**
     * 单个片段的软上限行数，超过则按行窗二次切分。
     */
    private int chunkMaxLines = 80;

    /**
     * 二次切分时的重叠行数。切点落在函数体中间时，没有重叠会让两半都缺上下文。
     */
    private int chunkOverlapLines = 8;

    /**
     * 生成完成后是否异步预热索引。不预热也能工作（检索前会懒索引），
     * 但那样第一次迭代要现场向量化整个工程，用户会多等若干秒。
     */
    private boolean indexOnComplete = true;

    /**
     * 检索召回评测集资源路径。与代码生成评测集分开：那套度量「生成得怎么样」，
     * 这套度量「检索得准不准」，口径不能混。
     */
    private String evalSetPath = "eval/rag-eval-set.json";

    /**
     * 向量化模型配置。与对话模型分开：它需要 embedding 接口，模型名与成本模型都不同。
     */
    private Embedding embedding = new Embedding();

    @Data
    public static class Embedding {

        /**
         * 接口地址。默认沿用生成模型的 base-url。
         */
        private String baseUrl;

        /**
         * 凭证。默认沿用生成模型的 key。
         */
        private String apiKey;

        /**
         * embedding 模型名，必须单独指定，与生成模型不是一回事。
         */
        private String modelName = "text-embedding-v4";

        /**
         * 单次请求携带的文本条数。自行分批以精确按真实 HTTP 请求数记预算、并避开单请求上限。
         */
        private int batchSize = 10;

        /**
         * 失败重试次数。向量化失败只影响检索增强，不值得重试太多次。
         */
        private int maxRetries = 2;

        /**
         * 启动时是否做一次真实往返自检，默认开。
         * <p>值得发这次请求：embedding 配错时应用照常启动、生成照常成功，只是检索悄悄退化成纯关键词，
         * 而日志里的 INFO 提示几乎必然被忽略；环境变量未注入时 {@code ${LLM_BASE_URL}} 会原样保留为
         * 字面量，配置读起来是齐的，真实请求却打到一个不存在的地址。代价只是一条十几 token 的请求。
         */
        private boolean selfCheck = true;
    }

    @PostConstruct
    public void init() {
        if (topK <= 0) {
            log.warn("RAG top-k 配置非法（{}），回退为 8", topK);
            topK = 8;
        }
        if (maxChunksPerFile <= 0) {
            maxChunksPerFile = 3;
        }
        if (maxContextChars <= 0) {
            maxContextChars = 12000;
        }
        if (chunkMaxLines <= 0) {
            chunkMaxLines = 80;
        }
        // 重叠必须小于上限，否则二次切分会原地踏步、切出无限个片段
        if (chunkOverlapLines < 0 || chunkOverlapLines >= chunkMaxLines) {
            log.warn("RAG 切块重叠行数非法（overlap={}, maxLines={}），回退为 8",
                    chunkOverlapLines, chunkMaxLines);
            chunkOverlapLines = Math.min(8, chunkMaxLines - 1);
        }
        if (vectorWeight < 0 || keywordWeight < 0
                || (vectorWeight + keywordWeight) <= 0) {
            log.warn("RAG 检索权重配置非法（vector={}, keyword={}），回退为 0.7 / 0.3",
                    vectorWeight, keywordWeight);
            vectorWeight = 0.7D;
            keywordWeight = 0.3D;
        }
        // 折扣只在 (0,1) 开区间有意义；其余（含 NaN）一律按关闭处理，避免非法配置静默放大或翻转分数
        if (!(genericFilePenalty > 0D) || genericFilePenalty >= 1.0D) {
            if (genericFilePenalty != 1.0D) {
                log.warn("RAG 通用文件折扣非法（{}），按不打折处理", genericFilePenalty);
            }
            genericFilePenalty = 1.0D;
        }
        if (embedding == null) {
            embedding = new Embedding();
        }
        if (embedding.getBatchSize() <= 0) {
            embedding.setBatchSize(10);
        }
        log.info("RAG 配置：enabled={}, topK={}, 单文件上限={}, 上下文上限={} 字符, "
                        + "权重=向量 {} / 关键词 {}, 通用文件折扣={}, 切块={} 行（重叠 {}）, "
                        + "embedding 模型={}（启动自检={}）",
                enabled, topK, maxChunksPerFile, maxContextChars,
                vectorWeight, keywordWeight, genericFilePenalty,
                chunkMaxLines, chunkOverlapLines,
                embedding.getModelName(), embedding.isSelfCheck());
    }
}
