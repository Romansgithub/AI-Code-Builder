package com.quan.aicodehelper.core.rag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 代码切块测试。
 *
 * <p>这块逻辑决定了检索的颗粒度，一旦出错影响是双向的：切太碎会让模型
 * 看到半个函数（改不对），切太粗会让检索命中率下降（捞不到该改的地方）。
 * 所以测试既守「边界是否正确」，也守「内容有没有丢」。</p>
 */
@DisplayName("代码切块")
class CodeChunkerTest {

    private static final String FILE_HASH = "file-hash-fixture";
    private static final int MAX_LINES = 80;
    private static final int OVERLAP_LINES = 8;

    private static List<CodeSlice> chunk(String path, String content) {
        return CodeChunker.chunk(path, content, FILE_HASH, MAX_LINES, OVERLAP_LINES);
    }

    @Nested
    @DisplayName("Vue 单文件组件")
    class VueCase {

        private final String vue = String.join("\n",
                "<template>",
                "  <div>登录</div>",
                "</template>",
                "<script setup>",
                "const title = ref('x')",
                "</script>",
                "<style scoped>",
                ".btn { color: red; }",
                "</style>");

        @Test
        @DisplayName("应按 template / script / style 三块切开，而不是整份当一段")
        void splitsIntoSfcBlocks() {
            List<CodeSlice> slices = chunk("src/views/Login.vue", vue);

            assertThat(slices).hasSize(3);
            assertThat(slices).extracting(CodeSlice::startLine).containsExactly(1, 4, 7);
            assertThat(slices).extracting(CodeSlice::endLine).containsExactly(3, 6, 9);
        }

        @Test
        @DisplayName("片段内容应是对应块的原文，不含任何额外头部文字")
        void contentIsVerbatim() {
            List<CodeSlice> slices = chunk("src/views/Login.vue", vue);

            assertThat(slices.get(0).content())
                    .isEqualTo("<template>\n  <div>登录</div>\n</template>");
            assertThat(slices.get(1).content())
                    .isEqualTo("<script setup>\nconst title = ref('x')\n</script>");
        }

        @Test
        @DisplayName("送给向量化的文本才带路径与行号 —— 位置语义不该污染片段原文")
        void embeddingTextCarriesLocation() {
            List<CodeSlice> slices = chunk("src/views/Login.vue", vue);

            assertThat(slices.get(0).toEmbeddingText())
                    .startsWith("[文件] src/views/Login.vue [行] 1-3")
                    .contains("<div>登录</div>");
            assertThat(slices.get(0).content()).doesNotContain("[文件]");
        }
    }

    @Nested
    @DisplayName("超长片段的二次切分")
    class WindowingCase {

        private final String longFile = buildFlatFile(200);

        @Test
        @DisplayName("超过上限的片段应按行窗切开，而不是产出超长片段")
        void splitsByWindow() {
            List<CodeSlice> slices = chunk("src/data.txt", longFile);

            assertThat(slices).hasSize(3);
            assertThat(slices).extracting(CodeSlice::startLine).containsExactly(1, 73, 145);
            assertThat(slices).extracting(CodeSlice::endLine).containsExactly(80, 152, 200);
        }

        @Test
        @DisplayName("相邻窗口必须重叠 —— 否则切点落在函数体中段时两半都缺上下文")
        void windowsOverlap() {
            List<CodeSlice> slices = chunk("src/data.txt", longFile);

            // 第 1 段止于 80 行，第 2 段起于 73 行 ⇒ 重叠 8 行，与配置一致
            assertThat(slices.get(0).endLine() - slices.get(1).startLine() + 1)
                    .isEqualTo(OVERLAP_LINES);
        }

        @Test
        @DisplayName("每一行都必须至少落在一个片段里 —— 切块绝不能丢内容")
        void coversEveryLine() {
            List<CodeSlice> slices = chunk("src/data.txt", longFile);
            int totalLines = longFile.split("\n").length;

            for (int line = 1; line <= totalLines; line++) {
                int current = line;
                boolean covered = slices.stream()
                        .anyMatch(s -> s.startLine() <= current && s.endLine() >= current);
                assertThat(covered)
                        .as("第 %d 行应至少被一个片段覆盖", current)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("重叠不小于上限这种退化配置下仍必须终止，不能死循环")
        void degenerateOverlapStillTerminates() {
            // 生产路径上不会出现这种配置（RagProperties 会把重叠夹到上限以下），
            // 这里锁的是「切块器自己也不能因为配置错误而转不出来」
            List<CodeSlice> slices = CodeChunker.chunk("src/data.txt", longFile,
                    FILE_HASH, 10, 10);

            assertThat(slices).isNotEmpty();
            assertThat(slices.size()).isLessThanOrEqualTo(200);
        }

        private static String buildFlatFile(int lines) {
            List<String> content = new ArrayList<>(lines);
            for (int i = 0; i < lines; i++) {
                content.add("line-" + String.format("%03d", i));
            }
            return String.join("\n", content);
        }
    }

    @Nested
    @DisplayName("声明边界（Java / TS）")
    class DeclarationCase {

        @Test
        @DisplayName("类成员与类声明应切开，方法体内的深缩进行不应切开")
        void splitsOnDeclarationIndent() {
            String java = String.join("\n",
                    "public class Foo {",
                    "    private String name;",
                    "    public void run() {",
                    "        if (name == null) {",
                    "            int y = 1;",
                    "        }",
                    "    }",
                    "}");

            List<CodeSlice> slices = chunk("src/main/java/Foo.java", java);

            assertThat(slices).hasSize(3);
            assertThat(slices).extracting(CodeSlice::startLine).containsExactly(1, 2, 3);
            // 第三段吞掉了方法体剩余部分 —— 8 空格以上的缩进不该成为边界
            assertThat(slices.get(2).endLine()).isEqualTo(8);
        }
    }

    @Nested
    @DisplayName("边界情形")
    class EdgeCase {

        @Test
        @DisplayName("空内容不产生片段")
        void blankContent() {
            assertThat(chunk("a.txt", "")).isEmpty();
            assertThat(CodeChunker.chunk("a.txt", null, FILE_HASH, MAX_LINES, OVERLAP_LINES))
                    .isEmpty();
        }

        @Test
        @DisplayName("CRLF 应归一为 LF —— 否则同一份逻辑内容会算出不同摘要，增量索引失效")
        void normalizesCrlf() {
            List<CodeSlice> slices = chunk("a.txt", "a\r\nb\r\nc");

            assertThat(slices).hasSize(1);
            assertThat(slices.get(0).content()).isEqualTo("a\nb\nc");
            assertThat(slices.get(0).content()).doesNotContain("\r");
        }

        @Test
        @DisplayName("末尾换行不应多出一行，否则行号会与编辑器对不上")
        void trailingNewlineIsNotALine() {
            assertThat(chunk("a.txt", "x\ny\n").get(0).endLine()).isEqualTo(2);
            assertThat(chunk("a.txt", "x\ny").get(0).endLine()).isEqualTo(2);
        }

        @Test
        @DisplayName("chunkIndex 应在整个文件内连续编号")
        void chunkIndexIsSequential() {
            List<CodeSlice> slices = chunk("src/views/Login.vue", String.join("\n",
                    "<template>", "  <div/>", "</template>",
                    "<script setup>", "const a = 1", "</script>"));

            assertThat(slices).extracting(CodeSlice::chunkIndex).containsExactly(0, 1);
        }

        @Test
        @DisplayName("片段应带上文件摘要，供增量索引判断整个文件变没变")
        void carriesFileHash() {
            assertThat(chunk("a.txt", "x").get(0).fileHash()).isEqualTo(FILE_HASH);
        }

        @Test
        @DisplayName("内容摘要应稳定，且内容不同则摘要不同")
        void contentHashIsStableAndDiscriminating() {
            String a1 = chunk("a.txt", "const a = 1").get(0).contentHash();
            String a2 = chunk("a.txt", "const a = 1").get(0).contentHash();
            String b = chunk("a.txt", "const a = 2").get(0).contentHash();

            assertThat(a1).isEqualTo(a2).isNotEqualTo(b);
        }

        @Test
        @DisplayName("应抽取标识符与中文字面量，供关键词那一路检索")
        void extractsSymbols() {
            List<CodeSlice> slices = chunk("src/views/Login.vue", String.join("\n",
                    "<template>", "  <h1>登录</h1>", "</template>",
                    "<script setup>", "const loginButton = 1", "</script>"));

            assertThat(slices.get(0).symbols()).contains("登录");
            assertThat(slices.get(1).symbols()).contains("loginbutton");
            // 语言关键字不该进符号表，否则会命中几乎所有片段
            assertThat(slices.get(1).symbols()).doesNotContain("const");
        }

        @Test
        @DisplayName("位置标签应含文件路径与行号，便于模型按路径回溯原文")
        void locationLabel() {
            assertThat(chunk("src/App.vue", "<template/>").get(0).locationLabel())
                    .isEqualTo("src/App.vue（第 1-1 行）");
        }
    }
}
