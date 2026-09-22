package com.quan.aicodehelper.core.builder;

import com.quan.aicodehelper.config.BuilderProperties;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Vue 项目构建器测试。
 *
 * <p>只覆盖<b>不触发 npm 执行</b>的路径：目录校验与失败类型判定。
 * 真正跑 npm 的路径依赖网络与 Node 环境，属集成测试范畴，不在此处执行。</p>
 */
@DisplayName("Vue 项目构建器")
class VueProjectBuilderTest {

    private static Path workDir;

    private final VueProjectBuilder builder = new VueProjectBuilder();

    @BeforeAll
    static void setUpWorkspace() throws IOException {
        workDir = Files.createTempDirectory("vue-builder-test-");
    }

    @AfterAll
    static void cleanUp() throws IOException {
        if (workDir != null && Files.exists(workDir)) {
            try (var paths = Files.walk(workDir)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
    }

    @Nested
    @DisplayName("项目结构校验")
    class ProjectValidation {

        @Test
        @DisplayName("目录不存在时应判为 INVALID_PROJECT 且不可自愈")
        void missingDirectory() {
            BuildResult result = builder.buildProjectWithResult(
                    workDir.resolve("not-exist-dir").toString());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureType()).isEqualTo(BuildFailureTypeEnum.INVALID_PROJECT);
            // 自愈不应被触发 —— 重试改变不了目录不存在的事实
            assertThat(result.isHealable()).isFalse();
        }

        @Test
        @DisplayName("路径指向文件而非目录时同样判为 INVALID_PROJECT")
        void pathIsFile() throws IOException {
            Path file = workDir.resolve("a-file.txt");
            Files.writeString(file, "not a dir", StandardCharsets.UTF_8);

            BuildResult result = builder.buildProjectWithResult(file.toString());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureType()).isEqualTo(BuildFailureTypeEnum.INVALID_PROJECT);
        }

        @Test
        @DisplayName("缺少 package.json 时应判为 INVALID_PROJECT，不该走到 npm 执行")
        void missingPackageJson() throws IOException {
            Path dir = Files.createDirectory(workDir.resolve("no-package-json"));

            BuildResult result = builder.buildProjectWithResult(dir.toString());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getFailureType()).isEqualTo(BuildFailureTypeEnum.INVALID_PROJECT);
            assertThat(result.getErrorMessage()).contains("package.json");
            assertThat(result.isHealable()).isFalse();
        }
    }

    @Nested
    @DisplayName("依赖安装判定")
    class DependencyInstallation {

        @Test
        @DisplayName("node_modules 不存在时必须安装依赖")
        void shouldInstallWhenNodeModulesMissing() throws IOException {
            Path dir = Files.createDirectory(workDir.resolve("no-node-modules"));
            Files.writeString(dir.resolve("package.json"), "{}", StandardCharsets.UTF_8);

            assertThat(invokeShouldInstall(dir.toFile())).isTrue();
        }

        @Test
        @DisplayName("node_modules 存在且 package.json 更旧时应跳过安装 —— 这是自愈能快速重试的关键")
        void shouldSkipWhenUpToDate() throws IOException, InterruptedException {
            Path dir = Files.createDirectory(workDir.resolve("up-to-date"));
            Files.writeString(dir.resolve("package.json"), "{}", StandardCharsets.UTF_8);
            Files.createDirectory(dir.resolve("node_modules"));
            // 让 node_modules 的修改时间晚于 package.json
            Thread.sleep(20);
            Files.setLastModifiedTime(dir.resolve("node_modules"),
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));

            assertThat(invokeShouldInstall(dir.toFile())).isFalse();
        }

        @Test
        @DisplayName("package.json 比 node_modules 新时必须重新安装（依赖有变更）")
        void shouldInstallWhenPackageJsonNewer() throws IOException, InterruptedException {
            Path dir = Files.createDirectory(workDir.resolve("package-newer"));
            Files.createDirectory(dir.resolve("node_modules"));
            Thread.sleep(20);
            Files.writeString(dir.resolve("package.json"), "{}", StandardCharsets.UTF_8);

            assertThat(invokeShouldInstall(dir.toFile())).isTrue();
        }
    }

    @Nested
    @DisplayName("兼容旧接口")
    class LegacyInterface {

        @Test
        @DisplayName("buildProject 对无效目录应返回 false")
        void legacyReturnsFalseForInvalid() {
            assertThat(builder.buildProject(workDir.resolve("nope").toString())).isFalse();
        }
    }

    @Nested
    @DisplayName("npm 依赖源参数")
    class NpmRegistryArg {

        @Test
        @DisplayName("配了镜像后，按空格切分仍是一个完整参数（源地址不会被 split 切碎）")
        void appendsRegistryWhenConfigured() {
            setRegistry("https://registry.npmmirror.com");

            // 构建器用 command.split("\\s+") 拆命令：源地址里若含空格，会被拆成两个参数
            String command = "npm.cmd install" + invokeNpmRegistryArg();

            assertThat(command.split("\\s+")).containsExactly(
                    "npm.cmd", "install", "--registry=https://registry.npmmirror.com");
        }

        @Test
        @DisplayName("留空时应返回空串 —— 命令必须与改造前逐字一致，不偷偷改变行为")
        void appendsNothingWhenBlank() {
            setRegistry("   ");

            assertThat(invokeNpmRegistryArg()).isEmpty();
        }

        @Test
        @DisplayName("配置未装配时不应抛异常，同样不追加参数")
        void handlesMissingProperties() {
            ReflectionTestUtils.setField(builder, "builderProperties", null);

            assertThat(invokeNpmRegistryArg()).isEmpty();
        }

        private void setRegistry(String registry) {
            BuilderProperties props = new BuilderProperties();
            props.setNpmRegistry(registry);
            ReflectionTestUtils.setField(builder, "builderProperties", props);
        }
    }

    private String invokeNpmRegistryArg() {
        String result = ReflectionTestUtils.invokeMethod(builder, "npmRegistryArg");
        assertThat(result).isNotNull();
        return result;
    }

    /**
     * shouldInstallDependencies 是私有方法，通过反射调用。
     * 直接测它是为了把「跳过 npm install」这条优化锁死 —— 它直接决定自愈的响应速度。
     */
    private boolean invokeShouldInstall(java.io.File dir) {
        Boolean result = ReflectionTestUtils.invokeMethod(builder, "shouldInstallDependencies", dir);
        assertThat(result).isNotNull();
        return result;
    }
}
