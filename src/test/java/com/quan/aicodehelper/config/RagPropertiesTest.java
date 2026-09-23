package com.quan.aicodehelper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 配置校验测试。
 *
 * <p>这里的每条断言都在守一个「配错了不会报错、只会悄悄变坏」的口子：</p>
 * <ul>
 *   <li>切块上限为 0 会让每个文件只产出一个片段，检索颗粒度直接失效；</li>
 *   <li><b>重叠不小于切块上限</b>会让二次切分的步长退化为 0 或负数 ——
 *       那是死循环级别的故障，必须在这里夹住；</li>
 *   <li>权重全为 0 会让所有片段得分相同，排序变成随机顺序，
 *       而检索仍然「能返回结果」，看起来完全正常。</li>
 * </ul>
 * <p>配置默认值都合理，所以这些分支平时不会走到 —— 正因为如此，
 * 没测过就等于没写过。</p>
 */
@DisplayName("RAG 配置校验")
class RagPropertiesTest {

    @Test
    @DisplayName("默认值应直接可用：开关打开、权重可加、重叠小于切块上限")
    void defaultsAreSane() {
        RagProperties properties = new RagProperties();
        properties.init();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTopK()).isPositive();
        assertThat(properties.getMaxChunksPerFile()).isPositive();
        assertThat(properties.getMaxContextChars()).isPositive();
        assertThat(properties.getVectorWeight() + properties.getKeywordWeight()).isPositive();
        assertThat(properties.getChunkOverlapLines()).isLessThan(properties.getChunkMaxLines());
        assertThat(properties.getEmbedding().getModelName()).isNotBlank();
        assertThat(properties.getEmbedding().getBatchSize()).isPositive();
    }

    @Test
    @DisplayName("topK 非正时应回退为 8，否则一条片段都取不出来")
    void invalidTopKFallsBack() {
        RagProperties properties = new RagProperties();
        properties.setTopK(0);
        properties.init();

        assertThat(properties.getTopK()).isEqualTo(8);
    }

    @Test
    @DisplayName("重叠不小于切块上限时必须夹到上限以下 —— 否则二次切分步长归零")
    void overlapIsClampedBelowMaxLines() {
        RagProperties properties = new RagProperties();
        properties.setChunkMaxLines(5);
        properties.setChunkOverlapLines(5);
        properties.init();

        assertThat(properties.getChunkOverlapLines()).isLessThan(properties.getChunkMaxLines());
    }

    @Test
    @DisplayName("负的重叠行数应回退为默认值")
    void negativeOverlapFallsBack() {
        RagProperties properties = new RagProperties();
        properties.setChunkOverlapLines(-3);
        properties.init();

        assertThat(properties.getChunkOverlapLines()).isEqualTo(8);
    }

    @Test
    @DisplayName("权重非法时应整体回退，避免所有片段得分相同、排序变随机")
    void invalidWeightsFallBack() {
        RagProperties properties = new RagProperties();
        properties.setVectorWeight(-1D);
        properties.setKeywordWeight(0D);
        properties.init();

        assertThat(properties.getVectorWeight()).isEqualTo(0.7D);
        assertThat(properties.getKeywordWeight()).isEqualTo(0.3D);
    }

    @Test
    @DisplayName("默认不打折 —— 折扣会牺牲「要改全局样式却没点名」的需求，必须先实测再启用")
    void genericFilePenaltyIsOffByDefault() {
        RagProperties properties = new RagProperties();
        properties.init();

        assertThat(properties.getGenericFilePenalty())
                .as("第三轮实测里排后不产生漏召（除 1 条独立漏召外期望文件全在 Top-K 里），"
                        + "而折扣的代价是即时可见的，所以默认值必须是「行为与改造前一致」的 1.0")
                .isEqualTo(1.0D);
    }

    @Test
    @DisplayName("折扣系数越界（含 NaN）应归一为 1.0，而不是静默放大或翻转分数")
    void invalidGenericFilePenaltyFallsBack() {
        RagProperties negative = new RagProperties();
        negative.setGenericFilePenalty(-0.3D);
        negative.init();
        assertThat(negative.getGenericFilePenalty()).isEqualTo(1.0D);

        RagProperties tooLarge = new RagProperties();
        tooLarge.setGenericFilePenalty(1.5D);
        tooLarge.init();
        assertThat(tooLarge.getGenericFilePenalty()).isEqualTo(1.0D);

        RagProperties nan = new RagProperties();
        nan.setGenericFilePenalty(Double.NaN);
        nan.init();
        assertThat(nan.getGenericFilePenalty())
                .as("NaN 与任何数比较都是 false —— 漏掉这条分支会让向量分乘出 NaN，排序整体失效")
                .isEqualTo(1.0D);
    }

    @Test
    @DisplayName("合法折扣（0 与 1 之间）应原样保留")
    void validGenericFilePenaltyIsKept() {
        RagProperties properties = new RagProperties();
        properties.setGenericFilePenalty(0.9D);
        properties.init();

        assertThat(properties.getGenericFilePenalty()).isEqualTo(0.9D);
    }

    @Test
    @DisplayName("上下文上限非正时应回退为 12000，否则检索内容一条也放不进去")
    void invalidContextLimitFallsBack() {
        RagProperties properties = new RagProperties();
        properties.setMaxContextChars(0);
        properties.init();

        assertThat(properties.getMaxContextChars()).isEqualTo(12000);
    }

    @Test
    @DisplayName("批次大小非法时应回退为 10，否则向量化会原地不动")
    void invalidBatchSizeFallsBack() {
        RagProperties properties = new RagProperties();
        properties.getEmbedding().setBatchSize(0);
        properties.init();

        assertThat(properties.getEmbedding().getBatchSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("embedding 段缺失时要补出默认实例，否则 init 里读模型名就空指针")
    void missingEmbeddingSectionIsRestored() {
        RagProperties properties = new RagProperties();
        properties.setEmbedding(null);
        properties.init();

        assertThat(properties.getEmbedding()).isNotNull();
        assertThat(properties.getEmbedding().getModelName()).isNotBlank();
    }

    @Test
    @DisplayName("默认开启启动自检 —— embedding 配错只会静默降级，必须有地方主动报出来")
    void selfCheckIsEnabledByDefault() {
        RagProperties properties = new RagProperties();
        properties.init();

        assertThat(properties.getEmbedding().isSelfCheck())
                .as("配错 embedding 不会让生成失败，只会让检索悄悄退化成纯关键词；"
                        + "默认关掉自检等于放弃唯一的主动发现途径")
                .isTrue();
    }
}
