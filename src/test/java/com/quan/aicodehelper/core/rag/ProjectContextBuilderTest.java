package com.quan.aicodehelper.core.rag;

import com.quan.aicodehelper.config.RagProperties;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 项目上下文渲染测试。
 *
 * <p>这段文本最终会进 system message，所以两件事必须守住：
 * 渲染出来的东西得让模型能定位到真实文件（路径 + 行号 + 语言标记），
 * 以及总长度必须受控 —— prompt 预算被检索内容吃干净，
 * 比没有检索增强还糟。</p>
 */
@DisplayName("项目上下文渲染")
class ProjectContextBuilderTest {

    private RagProperties properties;
    private ProjectContextBuilder builder;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.init();
        builder = new ProjectContextBuilder();
        ReflectionTestUtils.setField(builder, "ragProperties", properties);
    }

    private static RetrievedSlice slice(String path, int from, int to, String content) {
        return new RetrievedSlice(path, from, to, content, 1.0D, 0.9D, 0.8D);
    }

    private static String filler(int length) {
        return "x".repeat(length);
    }

    @Nested
    @DisplayName("渲染内容")
    class RenderCase {

        @Test
        @DisplayName("应给出文件路径与行号，让模型能定位并回溯原文")
        void includesLocation() {
            String context = builder.build(CodeGenTypeEnum.VUE_PROJECT,
                    List.of(slice("src/views/Login.vue", 12, 58, "<template/>")));

            assertThat(context)
                    .contains("src/views/Login.vue（第 12-58 行）")
                    .contains("<template/>");
        }

        @Test
        @DisplayName("代码块应带语言标记，标错会让模型按另一种语法解读")
        void includesFenceLanguage() {
            String context = builder.build(CodeGenTypeEnum.VUE_PROJECT,
                    List.of(slice("src/views/Login.vue", 1, 2, "x"),
                            slice("src/api/user.ts", 3, 4, "y")));

            assertThat(context).contains("```vue").contains("```typescript");
        }

        @Test
        @DisplayName("应写明工程类型与片段来源，说明内容取自磁盘当前状态")
        void statesProvenance() {
            String context = builder.build(CodeGenTypeEnum.VUE_PROJECT,
                    List.of(slice("a.vue", 1, 1, "x")));

            assertThat(context)
                    .contains("Vue 工程模式")
                    .contains("vue_project")
                    .contains("磁盘上的真实代码");
        }

        @Test
        @DisplayName("必须说明这些代码块不是输出格式示例 —— 否则模型可能照抄格式")
        void warnsAboutFormat() {
            String context = builder.build(CodeGenTypeEnum.HTML, List.of(slice("a.html", 1, 1, "x")));

            assertThat(context).contains("不是输出格式示例");
        }

        @Test
        @DisplayName("片段顺序应保持检索给出的相关度顺序")
        void preservesOrder() {
            String context = builder.build(CodeGenTypeEnum.HTML,
                    List.of(slice("first.html", 1, 1, "A"), slice("second.html", 1, 1, "B")));

            assertThat(context.indexOf("first.html")).isLessThan(context.indexOf("second.html"));
        }
    }

    @Nested
    @DisplayName("长度预算")
    class BudgetCase {

        @Test
        @DisplayName("超出预算的片段应被丢弃，并说明还差多少未列出")
        void dropsOverBudgetSlices() {
            properties.setMaxContextChars(12_000);
            List<RetrievedSlice> slices = List.of(
                    slice("a.vue", 1, 100, filler(5000)),
                    slice("b.vue", 1, 100, filler(5000)),
                    slice("c.vue", 1, 100, filler(5000)));

            String context = builder.build(CodeGenTypeEnum.VUE_PROJECT, slices);

            assertThat(context.length()).isLessThanOrEqualTo(12_000);
            assertThat(context).contains("a.vue").contains("b.vue");
            assertThat(context).doesNotContain("c.vue");
            assertThat(context).contains("因上下文长度限制未列出");
        }

        @Test
        @DisplayName("单条片段就超预算时也要保留一条（截断），不能返回空上下文")
        void keepsAtLeastOneSlice() {
            properties.setMaxContextChars(500);
            String context = builder.build(CodeGenTypeEnum.VUE_PROJECT,
                    List.of(slice("big.vue", 1, 100, filler(5000))));

            assertThat(context).isNotEmpty();
            assertThat(context).contains("big.vue").contains("片段已截断");
        }

        @Test
        @DisplayName("预算充足时不应出现丢弃提示")
        void noDropNoticeWhenEverythingFits() {
            String context = builder.build(CodeGenTypeEnum.VUE_PROJECT,
                    List.of(slice("a.vue", 1, 1, "x")));

            // 只断言「丢弃提示」这句本身：页脚里另有「未列出文件」的说法，
            // 用短词断言会误命中它
            assertThat(context).doesNotContain("因上下文长度限制未列出");
        }
    }

    @Nested
    @DisplayName("空上下文")
    class EmptyCase {

        @Test
        @DisplayName("没有检索结果时返回空串 —— 调用方直接透传即可，行为等同改造前")
        void noSlices() {
            assertThat(builder.build(CodeGenTypeEnum.HTML, List.of())).isEmpty();
            assertThat(builder.build(CodeGenTypeEnum.HTML, null)).isEmpty();
        }

        @Test
        @DisplayName("生成类型未知时不渲染")
        void nullType() {
            assertThat(builder.build(null, List.of(slice("a.vue", 1, 1, "x")))).isEmpty();
        }

        @Test
        @DisplayName("开关关闭时不渲染，即使有检索结果")
        void disabled() {
            properties.setEnabled(false);
            assertThat(builder.build(CodeGenTypeEnum.HTML, List.of(slice("a.html", 1, 1, "x"))))
                    .isEmpty();
        }
    }
}
