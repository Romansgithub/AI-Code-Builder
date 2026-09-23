package com.quan.aicodehelper.config;

import com.quan.aicodehelper.constant.AppConstant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储与部署配置测试：锁死部署地址尾斜杠补全与回填 AppConstant 两个行为。
 */
@DisplayName("存储与部署配置")
class StoragePropertiesTest {

    /** 记录类加载时的原始值，供每个用例结束后还原，避免全局静态状态在测试间互相污染 */
    private static final String ORIGINAL_OUTPUT = AppConstant.getCodeOutputRootDir();
    private static final String ORIGINAL_DEPLOY = AppConstant.getCodeDeployRootDir();
    private static final String ORIGINAL_HOST = AppConstant.getCodeDeployHost();

    @org.junit.jupiter.api.AfterEach
    void restoreAppConstant() {
        AppConstant.Storage.apply(ORIGINAL_OUTPUT, ORIGINAL_DEPLOY, ORIGINAL_HOST);
    }

    private StorageProperties newProps(String outputDir, String deployDir, String host) {
        StorageProperties p = new StorageProperties();
        p.setCodeOutputDir(outputDir);
        p.setCodeDeployDir(deployDir);
        p.setDeployHost(host);
        p.init();
        return p;
    }

    @Nested
    @DisplayName("部署地址拼接")
    class DeployUrl {

        @Test
        @DisplayName("host 以 / 结尾时应拼出带尾斜杠的完整地址")
        void hostWithTrailingSlash() {
            StorageProperties p = newProps(null, null, "http://localhost:8080/api/static/");
            assertThat(p.buildDeployUrl("AbC123")).isEqualTo("http://localhost:8080/api/static/AbC123/");
        }

        @Test
        @DisplayName("host 缺少尾斜杠时应自动补上（否则相对路径资源会 404）")
        void hostWithoutTrailingSlash() {
            StorageProperties p = newProps(null, null, "http://localhost:8080/api/static");
            assertThat(p.buildDeployUrl("AbC123")).isEqualTo("http://localhost:8080/api/static/AbC123/");
        }

        @Test
        @DisplayName("host 为空时应回退到默认值，且仍保证尾斜杠")
        void hostBlankFallsBack() {
            StorageProperties p = newProps(null, null, "");
            assertThat(p.getDeployHost()).isEqualTo("http://localhost:8080/api/static/");
            assertThat(p.buildDeployUrl("key")).endsWith("/key/");
        }
    }

    @Nested
    @DisplayName("配置回填到 AppConstant")
    class ApplyToAppConstant {

        @Test
        @DisplayName("初始化后 AppConstant 应读到注入的路径")
        void appConstantReflectsInjectedValues() {
            newProps("/tmp/custom-output", "/tmp/custom-deploy", "http://example.com/static/");
            assertThat(AppConstant.getCodeOutputRootDir()).isEqualTo("/tmp/custom-output");
            assertThat(AppConstant.getCodeDeployRootDir()).isEqualTo("/tmp/custom-deploy");
            assertThat(AppConstant.getCodeDeployHost()).isEqualTo("http://example.com/static/");
        }

        @Test
        @DisplayName("getter 不返回 null 或空串")
        void gettersAreUsable() {
            newProps(null, null, null);
            assertThat(AppConstant.getCodeOutputRootDir()).isNotBlank();
            assertThat(AppConstant.getCodeDeployRootDir()).isNotBlank();
            assertThat(AppConstant.getCodeDeployHost()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Path 视图")
    class PathView {

        @Test
        @DisplayName("应返回绝对路径")
        void pathsAreAbsolute() {
            StorageProperties p = newProps(null, null, null);
            assertThat(p.codeOutputPath().isAbsolute()).isTrue();
            assertThat(p.codeDeployPath().isAbsolute()).isTrue();
        }

        @Test
        @DisplayName("ensureDirectories 后目录应真实存在")
        void directoriesCreated() {
            StorageProperties p = newProps(
                    System.getProperty("java.io.tmpdir") + "/aich-test-output",
                    System.getProperty("java.io.tmpdir") + "/aich-test-deploy",
                    null);
            p.ensureDirectories();
            assertThat(p.codeOutputPath().toFile()).exists().isDirectory();
            assertThat(p.codeDeployPath().toFile()).exists().isDirectory();
        }
    }
}
