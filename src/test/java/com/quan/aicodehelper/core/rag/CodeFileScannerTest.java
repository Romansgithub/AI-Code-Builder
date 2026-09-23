package com.quan.aicodehelper.core.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工程目录扫描测试。测的是「闸门」而不是「遍历」：过滤失效会让 {@code node_modules}
 * 被索引，片段量从几百涨到几万，第三方库代码挤满 Top-K，检索质量崩塌并烧掉向量化额度。
 */
@DisplayName("工程目录扫描")
class CodeFileScannerTest {

    private static final long LARGE_LIMIT = 1_000_000L;

    @TempDir
    Path root;

    @BeforeEach
    void setUp() throws IOException {
        write("src/App.vue", "<template><div/></template>");
        write("src/util.js", "export const a = 1");
        write("src/nested/deep/Comp.vue", "<template><p/></template>");
        write("package.json", "{\"name\":\"demo\"}");
        write("README.md", "# demo");

        // 以下全部应当被排除
        write("node_modules/vue/index.js", "module.exports = {}");
        write("dist/index.html", "<html></html>");
        write("build/bundle.js", "var x = 1");
        write(".git/config", "[core]");
        write(".eslintrc.json", "{}");
        write("package-lock.json", "{\"lockfileVersion\":3}");
        write("src/logo.png", "not-really-a-png");
    }

    @Nested
    @DisplayName("排除规则")
    class ExclusionCase {

        @Test
        @DisplayName("node_modules / dist / build 等构建产物必须排除，否则检索质量会崩塌")
        void excludesBuildArtifacts() {
            List<String> paths = scannedPaths(LARGE_LIMIT);

            assertThat(paths).noneMatch(p -> p.contains("node_modules"));
            assertThat(paths).noneMatch(p -> p.startsWith("dist/"));
            assertThat(paths).noneMatch(p -> p.startsWith("build/"));
        }

        @Test
        @DisplayName("隐藏目录与隐藏文件必须排除（.git / .eslintrc 与需求无关）")
        void excludesHiddenEntries() {
            List<String> paths = scannedPaths(LARGE_LIMIT);

            assertThat(paths).noneMatch(p -> p.contains(".git"));
            assertThat(paths).noneMatch(p -> p.contains(".eslintrc"));
        }

        @Test
        @DisplayName("锁文件必须排除 —— 几万行校验和既无检索价值又占片段名额")
        void excludesLockFiles() {
            assertThat(scannedPaths(LARGE_LIMIT)).doesNotContain("package-lock.json");
        }

        @Test
        @DisplayName("非文本扩展名必须排除，免得把二进制读成乱码喂给嵌入模型")
        void excludesBinaryByWhitelist() {
            assertThat(scannedPaths(LARGE_LIMIT)).doesNotContain("src/logo.png");
        }

        @Test
        @DisplayName("超过大小上限的文件应被跳过，且原因是大小而不是扩展名")
        void excludesOversizedFiles() throws IOException {
            write("src/huge.js", "x".repeat(5000));

            assertThat(scannedPaths(1000L)).doesNotContain("src/huge.js");
            // 放宽上限后同名的 .js 应当被收进来，证明挡下它的是大小条件
            assertThat(scannedPaths(LARGE_LIMIT)).contains("src/huge.js");
        }
    }

    @Nested
    @DisplayName("产物内容")
    class ResultCase {

        @Test
        @DisplayName("应保留可直接检索的文本文件")
        void keepsIndexableFiles() {
            assertThat(scannedPaths(LARGE_LIMIT))
                    .contains("src/App.vue", "src/util.js", "src/nested/deep/Comp.vue",
                            "package.json", "README.md");
        }

        @Test
        @DisplayName("路径必须用正斜杠，否则同一份工程在两台机器上会得到两套片段标识")
        void relativePathsUseForwardSlash() {
            assertThat(scannedPaths(LARGE_LIMIT)).allMatch(p -> !p.contains("\\"));
        }

        @Test
        @DisplayName("顺序必须稳定 —— 否则每次重建索引 chunkIndex 全量漂移，增量比对退化成全量")
        void orderIsDeterministic() {
            List<String> first = scannedPaths(LARGE_LIMIT);
            List<String> second = scannedPaths(LARGE_LIMIT);

            assertThat(first).isEqualTo(second);
            assertThat(first).isSorted();
        }

        @Test
        @DisplayName("CRLF 应归一为 LF，否则同一份逻辑内容会算出不同摘要")
        void normalizesLineEndings() throws IOException {
            write("src/crlf.js", "const a = 1\r\nconst b = 2\r\n");

            CodeFileScanner.SourceFile file = scan(LARGE_LIMIT).stream()
                    .filter(f -> f.relativePath().equals("src/crlf.js"))
                    .findFirst()
                    .orElseThrow();

            assertThat(file.content()).isEqualTo("const a = 1\nconst b = 2\n");
        }

        @Test
        @DisplayName("内容相同的文件摘要相同，内容不同则摘要不同")
        void fileHashTracksContent() throws IOException {
            write("src/same1.js", "const a = 1");
            write("src/same2.js", "const a = 1");
            write("src/diff.js", "const a = 2");

            List<CodeFileScanner.SourceFile> files = scan(LARGE_LIMIT);
            String h1 = hashOf(files, "src/same1.js");
            String h2 = hashOf(files, "src/same2.js");
            String h3 = hashOf(files, "src/diff.js");

            assertThat(h1).isEqualTo(h2).isNotEqualTo(h3);
        }

        @Test
        @DisplayName("空文件不产生可索引条目")
        void skipsBlankFiles() throws IOException {
            write("src/empty.js", "   ");
            assertThat(scannedPaths(LARGE_LIMIT)).doesNotContain("src/empty.js");
        }

        @Test
        @DisplayName("目录不存在时返回空列表而不是抛异常")
        void missingDirectory() {
            assertThat(CodeFileScanner.scan(root.resolve("not-exists"), LARGE_LIMIT)).isEmpty();
            assertThat(CodeFileScanner.scan(null, LARGE_LIMIT)).isEmpty();
        }
    }

    private List<CodeFileScanner.SourceFile> scan(long maxFileBytes) {
        return CodeFileScanner.scan(root, maxFileBytes);
    }

    private List<String> scannedPaths(long maxFileBytes) {
        return scan(maxFileBytes).stream().map(CodeFileScanner.SourceFile::relativePath).toList();
    }

    private String hashOf(List<CodeFileScanner.SourceFile> files, String relativePath) {
        return files.stream()
                .filter(f -> f.relativePath().equals(relativePath))
                .findFirst()
                .orElseThrow()
                .fileHash();
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = root.resolve(relativePath);
        // Files.writeString 不会创建缺失的上级目录，不建父目录会抛 NoSuchFileException
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
