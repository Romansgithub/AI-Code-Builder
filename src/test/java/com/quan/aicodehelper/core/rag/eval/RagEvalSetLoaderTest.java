package com.quan.aicodehelper.core.rag.eval;

import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.model.dto.rag.RagEvalApp;
import com.quan.aicodehelper.model.dto.rag.RagEvalSet;
import com.quan.aicodehelper.model.enums.RetrievalKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 检索评测集的加载与校验。配置错误必须在跑之前炸掉：评测集的产物是会被写进文档与答辩
 * 材料的召回率数字，而配置错误在这里的表现是「指标静默变 0」，看起来像检索做得差。
 */
@DisplayName("检索评测集加载与校验")
class RagEvalSetLoaderTest {

    private RagEvalSetLoader loader;

    @BeforeEach
    void setUp() {
        loader = new RagEvalSetLoader();
        ReflectionTestUtils.setField(loader, "ragProperties", new RagProperties());
    }

    @Test
    @DisplayName("真实评测集应能加载，且语义型样本足够撑起「向量有效」的统计结论")
    void loadsRealEvalSet() {
        RagEvalSet set = loader.load();

        assertThat(set.getVersion()).isNotBlank();
        assertThat(set.getApps()).hasSize(2);
        assertThat(set.getCases()).hasSize(28);
        assertThat(countOf(set, RetrievalKind.POINTED))
                .as("点名型与语义型必须都有足够样本，否则消融时会得出「向量没用」的假结论")
                .isEqualTo(8);
        // 20 这个下限不是凑数：符号检验要 p<0.05 至少需要 6 个单向不一致对，样本再少就是拟合噪声
        assertThat(countOf(set, RetrievalKind.SEMANTIC))
                .as("语义型样本不足时，「向量把召回从 50% 提到 87.5%」这类结论在统计上站不住")
                .isGreaterThanOrEqualTo(20);

        Set<String> appKeys = set.getApps().stream()
                .map(RagEvalApp::getKey).collect(Collectors.toSet());
        assertThat(set.getCases()).allSatisfy(c -> {
            assertThat(appKeys).contains(c.getAppKey());
            assertThat(c.getExpectedFiles())
                    .as("标准答案非空，否则算不出召回率")
                    .isNotEmpty();
            assertThat(c.getExpectedFiles()).allSatisfy(file ->
                    assertThat(file)
                            .as("期望文件必须是正斜杠相对路径，否则永远比对不上检索结果")
                            .doesNotContain("\\")
                            .doesNotStartWith("/"));
        });
    }

    @Test
    @DisplayName("appId 为非正数必须直接报错 —— 非正数会让检索返回空，表现为「召回率 0%」而不是配置错误")
    void nonPositiveAppIdIsRejected() {
        RagEvalSet parsed = loader.parse(sampleJson("-1", "\"tour\"", "[\"src/App.vue\"]", "\"c1\""));

        assertThatThrownBy(() -> loader.validate(parsed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("appId");
    }

    @Test
    @DisplayName("用例引用了不存在的 appKey 必须报错 —— 否则这条会静默跑在空应用上，看起来像检索漏召")
    void unknownAppKeyIsRejected() {
        RagEvalSet parsed = loader.parse(sampleJson("1", "\"nope\"", "[\"src/App.vue\"]", "\"c1\""));

        assertThatThrownBy(() -> loader.validate(parsed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("appKey");
    }

    @Test
    @DisplayName("没有标注期望文件必须报错 —— 没有标准答案就算不出召回率，只能得到无意义的 0")
    void emptyExpectedFilesIsRejected() {
        RagEvalSet parsed = loader.parse(sampleJson("1", "\"tour\"", "[]", "\"c1\""));

        assertThatThrownBy(() -> loader.validate(parsed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expectedFiles");
    }

    @Test
    @DisplayName("期望文件写成反斜杠时要归一化成正斜杠，否则整份标注会全部比对不上")
    void backslashPathIsNormalized() {
        RagEvalSet parsed = loader.parse(
                sampleJson("1", "\"tour\"", "[\"src\\\\App.vue\"]", "\"c1\""));

        assertThat(parsed.getCases().get(0).getExpectedFiles())
                .as("检索结果里的 filePath 一律是正斜杠，标注时手滑写成反斜杠会全军覆没")
                .containsExactly("src/App.vue");
        assertThatCode(() -> loader.validate(parsed)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("期望文件跳出工程目录必须报错 —— 这种路径永远不可能等于检索结果，该用例会恒不命中")
    void escapingExpectedFileIsRejected() {
        RagEvalSet parsed = loader.parse(
                sampleJson("1", "\"tour\"", "[\"../secret/App.vue\"]", "\"c1\""));

        assertThatThrownBy(() -> loader.validate(parsed))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("跳出工程目录");
    }

    @Test
    @DisplayName("用例 id 重复必须报错 —— 报告按 id 定位用例，重复会让明细无法对应")
    void duplicateCaseIdIsRejected() {
        String json = """
                {
                  "name": "测试集",
                  "version": "v1.0",
                  "apps": [
                    { "key": "tour", "label": "旅游站", "appId": 1, "codeGenType": "vue_project" }
                  ],
                  "cases": [
                    { "id": "c1", "appKey": "tour", "kind": "SEMANTIC",
                      "query": "改导航", "expectedFiles": ["src/App.vue"] },
                    { "id": "c1", "appKey": "tour", "kind": "POINTED",
                      "query": "改页脚", "expectedFiles": ["src/App.vue"] }
                  ]
                }
                """;

        assertThatThrownBy(() -> loader.validate(loader.parse(json)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    @DisplayName("缺少 version 必须报错 —— 没有版本就无法跨批次比较指标")
    void missingVersionIsRejected() {
        String json = """
                {
                  "name": "测试集",
                  "apps": [
                    { "key": "tour", "label": "旅游站", "appId": 1, "codeGenType": "vue_project" }
                  ],
                  "cases": [
                    { "id": "c1", "appKey": "tour", "kind": "SEMANTIC",
                      "query": "改导航", "expectedFiles": ["src/App.vue"] }
                  ]
                }
                """;

        assertThatThrownBy(() -> loader.validate(loader.parse(json)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("version");
    }

    @Test
    @DisplayName("语义型 query 里出现文件名必须报错 —— 它会被关键词命中却仍计入语义型，把向量的收益算没")
    void semanticQueryWithFileNameIsRejected() {
        assertThatThrownBy(() -> loader.validate(loader.parse(
                caseJson("SEMANTIC", "把 App.vue 里那个导航项改一下"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件名线索")
                .hasMessageContaining(".vue");
    }

    @Test
    @DisplayName("语义型 query 里出现驼峰标识符必须报错 —— 用户视角的需求里不会有代码里的命名")
    void semanticQueryWithIdentifierIsRejected() {
        assertThatThrownBy(() -> loader.validate(loader.parse(
                caseJson("SEMANTIC", "想让草稿存在 localStorage 里，下次打开还在"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("代码标识符")
                .hasMessageContaining("localStorage");
    }

    @Test
    @DisplayName("点名型写了文件名是它的本分，不能被同一套线索规则误拦 —— 拦错了等于把这一类用例全废掉")
    void pointedQueryMayNameAFile() {
        RagEvalSet parsed = loader.parse(caseJson("POINTED", "把 App.vue 里那个导航项改一下"));

        assertThatCode(() -> loader.validate(parsed)).doesNotThrowAnyException();
        assertThat(parsed.getCases().get(0).getKind()).isEqualTo(RetrievalKind.POINTED);
    }

    private static long countOf(RagEvalSet set, RetrievalKind kind) {
        return set.getCases().stream().filter(c -> c.getKind() == kind).count();
    }

    /** 生成只含单条用例的最小评测集，把某个 query 字面量单独喂给校验 */
    private static String caseJson(String kind, String query) {
        return """
                {
                  "name": "测试集",
                  "version": "v1.0",
                  "apps": [
                    { "key": "tour", "label": "旅游站", "appId": 1, "codeGenType": "vue_project" }
                  ],
                  "cases": [
                    { "id": "c1", "appKey": "tour", "kind": "%s",
                      "query": "%s", "expectedFiles": ["src/App.vue"] }
                  ]
                }
                """.formatted(kind, query);
    }

    /**
     * 生成一份最小可用评测集，只把需要构造异常的字段参数化。参数保持原始 JSON 片段，
     * 因为校验针对的就是这些字面量，先解析再回写就测不到「标注写法本身有问题」的情形。
     */
    private static String sampleJson(String appId, String caseAppKey,
                                     String expectedFilesJson, String caseId) {
        return """
                {
                  "name": "测试集",
                  "version": "v1.0",
                  "apps": [
                    { "key": "tour", "label": "旅游站", "appId": %s, "codeGenType": "vue_project" }
                  ],
                  "cases": [
                    { "id": %s, "appKey": %s, "kind": "SEMANTIC",
                      "query": "把导航栏的背景改一下", "expectedFiles": %s }
                  ]
                }
                """.formatted(appId, caseId, caseAppKey, expectedFilesJson);
    }
}
