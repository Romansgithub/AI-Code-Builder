package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 项目代码检索（RAG）配置。
 *
 * <p><b>它解决什么问题</b>：用户在已有应用上提修改要求时（「把登录按钮改成蓝色」），
 * 模型需要知道当前代码长什么样。改造前只有两条路：</p>
 * <ol>
 *   <li>对话记忆里残留的旧代码 —— 它是「模型以为自己写过什么」，而磁盘上的
 *       代码可能已被构建自愈或文件修改工具改过，两者会漂移；</li>
 *   <li>让模型用【目录读取工具】【文件读取工具】自己去翻 —— 翻哪个文件全凭它猜，
 *       工程一大就翻不完整，而且是多轮额外调用。</li>
 * </ol>
 * <p>本配置开启的第三条路：把磁盘上的工程代码切块向量化，按语义检索
 * 最相关的片段直接送进上下文。<b>磁盘代码是唯一真相</b>，这也是它比
 * 对话记忆更可信的根本原因。</p>
 *
 * <p><b>为什么模型是「混合检索」而不是纯向量</b>：自然语言需求与代码之间
 * 存在语义鸿沟 ——「把登录按钮改成蓝色」与 {@code <Button type="primary">}
 * 的向量相似度未必高。因此最终得分为
 * {@code 向量余弦 × vector-weight + 关键词命中率 × keyword-weight}，
 * 再加上「需求里直接点名了文件名」的精确命中加成。纯向量在真实需求上经常
 * 捞回一堆语义相近但无关的片段，关键词那一路正是用来把它拉回来的。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {

    /**
     * 总开关。关闭后不索引、不检索，行为与改造前完全一致。
     */
    private boolean enabled = true;

    /**
     * 每次注入上下文的最大片段数。
     *
     * <p>给多了会挤占 prompt 预算、稀释注意力；给少了关键文件可能不在里面。
     * 配合 {@code max-context-chars} 双重约束 —— 片段数够了但字符数超了照样要截。</p>
     */
    private int topK = 8;

    /**
     * 单个文件最多贡献几个片段。
     *
     * <p>没有这个上限时，一个「薄」文件（比如某个被反复调用的工具模块）
     * 可能凭借高相似度占满整个 Top-K，把其它真正需要改的文件挤出去。</p>
     */
    private int maxChunksPerFile = 3;

    /**
     * 注入上下文的字符数硬上限。
     *
     * <p>按字符而不按 token 估：计算无需分词器、跨模型可比，
     * 且这里只需一个「够保守的上界」。中文与代码混合时约 1 字符 ≈ 0.5~1 token。</p>
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
     * 全局样式文件在<b>向量分</b>上的折扣系数。
     *
     * <p><b>默认 1.0 = 不打折，这是有意的。</b>第三轮实测里 {@code main.css}
     * 在 8 条「排后」用例中占了 4 次首位，看着像缺陷；但那批用例的期望文件
     * 全都在 Top-K 里（除了另一条独立漏召），<b>排后不产生漏召</b> ——
     * 它占的是上下文预算与模型注意力，工程文件数远小于 Top-K 时几乎无代价。</p>
     *
     * <p><b>第四轮实测（开 0.9）把上面那段话验成了结论</b>：排后条数确实从 8 降到 5、
     * {@code main.css} 的占位归零，但仅向量与混合组的<b>漏召从 1 条涨到 3 条</b>
     * （Recall@8 96.4% → 89.3%），净账是 Top-1 +1、Recall −2。而且排后减少的 3 条里
     * 有 2 条并非改善、是退化成漏召 —— <b>只看排后清单会得出与事实相反的结论</b>。
     * 那次误伤来自已被删掉的「入口名」判据（见 {@code QueryTerms#isGenericFile}），
     * 但结论对整个旋钮有效：它预防的代价 ≈ 0，它带来的风险是真实的。</p>
     *
     * <p>因此保持默认关闭。真要启用：调成 0.85 ~ 0.95，重跑检索评测，
     * 对比【排后清单】的榜首占位是否下降、<b>【漏召清单】是否变长</b>。
     * 两者一起看才作数 —— 只盯着占位下降会把「把正确文件也压下去了」当成成功。</p>
     *
     * <p>取值 ≥ 1 或 ≤ 0 时按「关闭」处理（见 {@code QueryTerms#genericFilePenalty}）。</p>
     */
    private double genericFilePenalty = 1.0D;

    /**
     * 超过此大小的文件直接跳过索引。
     *
     * <p>工程里偶尔会有生成出来的大文件（打包产物、数据表等），
     * 它们既不该被检索，向量化也纯属浪费额度。</p>
     */
    private long maxFileBytes = 200_000L;

    /**
     * 单个片段的软上限行数，超过则按行窗二次切分。
     */
    private int chunkMaxLines = 80;

    /**
     * 二次切分时的重叠行数。
     *
     * <p>重叠是必要的：切点恰好落在函数体中间时，没有重叠会让两半
     * 都缺上下文，检索到任何一半都不足以让模型改对代码。</p>
     */
    private int chunkOverlapLines = 8;

    /**
     * 生成完成后是否异步预热索引。
     *
     * <p>不预热也能工作（检索前会做一次懒索引），但那样「第一次迭代」
     * 要现场把整个工程向量化，用户会多等若干秒。预热把这份等待挪到
     * 用户还在看生成结果的那段时间里。</p>
     */
    private boolean indexOnComplete = true;

    /**
     * 检索召回评测集资源路径。
     *
     * <p>单独成集而不是并进代码生成评测集：那套度量「生成得怎么样」，
     * 这套度量「检索得准不准」，两者的口径不能混（生成评测必须隔离掉检索增强，
     * 否则跨版本指标不可比）。</p>
     */
    private String evalSetPath = "eval/rag-eval-set.json";

    /**
     * 向量化模型配置。刻意与对话模型分开 —— 它需要的是 embedding 接口，
     * 通常与生成模型不同名，且成本模型也不一样。
     */
    private Embedding embedding = new Embedding();

    @Data
    public static class Embedding {

        /**
         * 接口地址。默认沿用生成模型的 base-url —— 同一个服务商通常同时提供
         * chat 与 embedding 接口，没必要配两遍。
         */
        private String baseUrl;

        /**
         * 凭证。同样默认沿用生成模型的 key。
         */
        private String apiKey;

        /**
         * embedding 模型名。与生成模型不是一回事，必须单独指定。
         */
        private String modelName = "text-embedding-v4";

        /**
         * 单次请求携带的文本条数。
         *
         * <p>自己分批而不是交给 SDK：一是能精确按「真实 HTTP 请求数」记预算，
         * 二是批次大小可调以避开服务商的单请求上限。</p>
         */
        private int batchSize = 10;

        /**
         * 失败重试次数。向量化失败只影响检索增强，不值得重试太多次。
         */
        private int maxRetries = 2;

        /**
         * 启动时是否做一次真实往返自检，默认开。
         *
         * <p><b>为什么值得发这一次请求</b>：embedding 配错是本项目里最难自查的
         * 一类问题 —— 应用照常启动、生成照常成功，只是检索悄悄退化成纯关键词，
         * 而那条提示是 INFO 级、混在长长的生成日志里，几乎必然被忽略。
         * 更糟的是「环境变量没注入」这种情况：{@code ${LLM_BASE_URL}} 会被原样
         * 保留为字面量，配置读起来是「齐的」，真实请求却打到一个不存在的地址上。</p>
         *
         * <p>代价是一条十几 token 的请求，换来一个明确结论，很划算。
         * 不打算用向量检索的可以关掉。</p>
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
        // 折扣只在 (0,1) 开区间里有意义；其余（含 NaN）一律按「关闭」处理，
        // 让非法配置退化成与默认值相同的行为，而不是静默放大或翻转分数
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
