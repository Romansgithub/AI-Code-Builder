package com.quan.aicodehelper.core.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 构建错误提取测试：提取规则决定喂给模型的提示词内容，影响自愈成功率与 token 消耗。
 */
@DisplayName("构建错误提取器")
class BuildErrorExtractorTest {

    @Nested
    @DisplayName("噪音过滤")
    class NoiseFiltering {

        @Test
        @DisplayName("npm notice / warn 不应出现在结果里")
        void shouldFilterNpmNoise() {
            String raw = String.join("\n",
                    "npm notice New major version of npm available",
                    "npm warn deprecated inflight@1.0.2: this library is not supported",
                    "npm notice Changelog: https://github.com/npm/cli/releases",
                    "added 120 packages in 30s");

            String result = BuildErrorExtractor.extract(raw);

            assertThat(result).doesNotContain("npm notice");
            assertThat(result).doesNotContain("npm warn");
            assertThat(result).doesNotContain("added 120 packages");
        }

        @Test
        @DisplayName("vite 构建横幅与体积提示不应出现")
        void shouldFilterViteBanner() {
            String raw = String.join("\n",
                    "vite v5.0.0 building for production...",
                    "transforming...",
                    "✓ built in 2.30s",
                    "dist/assets/index-abc.js   120.50 kB │ gzip: 38.20 kB");

            String result = BuildErrorExtractor.extract(raw);

            assertThat(result).doesNotContain("building for production");
            assertThat(result).doesNotContain("gzip:");
            assertThat(result).doesNotContain("built in");
        }

        @Test
        @DisplayName("应剥离 ANSI 颜色码，避免污染提示词")
        void shouldStripAnsiCodes() {
            String raw = "\u001B[31merror\u001B[0m: Cannot find module './Missing.vue'";

            String result = BuildErrorExtractor.extract(raw);

            assertThat(result).doesNotContain("\u001B[");
            assertThat(result).contains("Cannot find module");
        }
    }

    @Nested
    @DisplayName("错误识别")
    class ErrorDetection {

        @Test
        @DisplayName("应提取 error 关键字所在行")
        void shouldExtractErrorLines() {
            String raw = String.join("\n",
                    "> app@0.0.0 build",
                    "> vite build",
                    "error during build:",
                    "Error: Could not resolve './components/Header.vue' from src/App.vue");

            String result = BuildErrorExtractor.extract(raw);

            assertThat(result).contains("Could not resolve");
            assertThat(result).contains("./components/Header.vue");
        }

        @Test
        @DisplayName("带文件路径与行号的行必须保留 —— 这是最能定位问题的信息")
        void shouldKeepFileLocations() {
            String raw = "src/views/Home.vue:12:5 - error TS2304: Cannot find name 'foo'.";

            String result = BuildErrorExtractor.extract(raw);

            assertThat(result).contains("src/views/Home.vue:12:5");
        }

        @Test
        @DisplayName("应带上错误行的下一行作为上下文（vite 常把原因拆成两行）")
        void shouldIncludeNextLineAsContext() {
            String raw = String.join("\n",
                    "error during build:",
                    "RollupError: Unexpected token");

            String result = BuildErrorExtractor.extract(raw);

            assertThat(result).contains("error during build");
            assertThat(result).contains("RollupError");
        }

        @Test
        @DisplayName("常见语法错误关键字都应被识别")
        void shouldRecognizeCommonErrors() {
            assertThat(BuildErrorExtractor.extract("SyntaxError: Unexpected token '}'")).contains("SyntaxError");
            assertThat(BuildErrorExtractor.extract("ReferenceError: defineProps is not defined")).contains("ReferenceError");
            assertThat(BuildErrorExtractor.extract("Unterminated string literal")).contains("Unterminated");
            assertThat(BuildErrorExtractor.extract("Module not found: Error: Can't resolve 'vue-router'")).contains("not found");
        }

        @Test
        @DisplayName("无任何错误特征时应退回非噪音行的尾部，而不是返回空")
        void shouldFallbackToTail() {
            String raw = String.join("\n",
                    "some random output line one",
                    "some random output line two",
                    "the actual failure reason is here");

            String result = BuildErrorExtractor.extract(raw);

            assertThat(result).isNotBlank();
            assertThat(result).contains("the actual failure reason is here");
        }

        @Test
        @DisplayName("全是噪音且无错误特征时必须返回空串，绝不能把噪音放回去")
        void allNoiseShouldReturnEmpty() {
            String raw = String.join("\n",
                    "npm notice New major version of npm available",
                    "npm warn deprecated inflight@1.0.2",
                    "added 120 packages in 30s",
                    "vite v5.0.0 building for production...",
                    "✓ built in 2.30s");

            String result = BuildErrorExtractor.extract(raw);

            // 回归点：早期实现会退回原输出尾部，把刚过滤的噪音又放回去
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("空输入应返回空串，不抛异常")
        void shouldHandleBlankInput() {
            assertThat(BuildErrorExtractor.extract(null)).isEmpty();
            assertThat(BuildErrorExtractor.extract("")).isEmpty();
            assertThat(BuildErrorExtractor.extract("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("长度控制")
    class LengthControl {

        @Test
        @DisplayName("超长错误应被截断，避免提示词 token 爆炸")
        void shouldTruncateLongError() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 500; i++) {
                sb.append("error line ").append(i).append(": something went wrong here\n");
            }

            String result = BuildErrorExtractor.extract(sb.toString(), 500);

            assertThat(result.length()).isLessThanOrEqualTo(500 + 40);
            assertThat(result).contains("已截断");
        }

        @Test
        @DisplayName("短错误不应被改动")
        void shouldNotTruncateShortError() {
            String raw = "error: Cannot find module './Missing.vue'";

            String result = BuildErrorExtractor.extract(raw, 3000);

            assertThat(result).doesNotContain("已截断");
            assertThat(result).isEqualTo(raw);
        }

        @Test
        @DisplayName("重复报错应去重（同一错误在多个文件出现时只留一条）")
        void shouldDeduplicate() {
            String raw = String.join("\n",
                    "error: Cannot find module './Foo.vue'",
                    "error: Cannot find module './Foo.vue'",
                    "error: Cannot find module './Foo.vue'");

            String result = BuildErrorExtractor.extract(raw);

            assertThat(result.split("\n")).hasSize(1);
        }
    }

    @Nested
    @DisplayName("错误行计数")
    class ErrorCounting {

        @Test
        @DisplayName("应统计出错误行数量，用于日志与进度提示")
        void shouldCountErrors() {
            String raw = String.join("\n",
                    "src/a.vue:1:1 - error: bad",
                    "npm notice noise here",
                    "src/b.vue:2:2 - error: worse");

            assertThat(BuildErrorExtractor.countErrorLines(raw)).isEqualTo(2);
        }

        @Test
        @DisplayName("空输入计数为 0")
        void shouldCountZeroForBlank() {
            assertThat(BuildErrorExtractor.countErrorLines(null)).isZero();
            assertThat(BuildErrorExtractor.countErrorLines("")).isZero();
        }
    }
}
