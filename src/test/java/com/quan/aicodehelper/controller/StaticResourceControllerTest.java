package com.quan.aicodehelper.controller;

import com.quan.aicodehelper.constant.AppConstant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 静态资源预览控制器测试。
 *
 * <p><b>回归背景</b>：该控制器曾用固定长度做 substring 计算资源路径
 * （{@code getRequestURI().substring(("/static/" + deployKey).length())}）。
 * 一旦在中间插入 context-path 剥离逻辑，偏移量就会错位，把末尾的 {@code "/"} 一并吃掉，
 * 使 resourcePath 退化为空串并触发 301 跳转到 {@code xxx//}（双斜杠），
 * 前端预览 iframe 表现为全量 404/500。</p>
 *
 * <p>本测试用 {@code standaloneSetup} 启动纯 MockMvc，不加载 Spring 容器、
 * 不依赖数据库/Redis/LLM，因此可快速、稳定、可复现地锁死上述行为。</p>
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@DisplayName("静态资源预览控制器")
class StaticResourceControllerTest {

    private static final String DEPLOY_KEY = "html_test_deploy_key";
    private static final String CONTEXT_PATH = "/api";

    private static Path previewDir;
    /** 真正的「越权目标」：位于预览根目录的上一级，控制器绝不允许读到 */
    private static Path outsideDir;
    private static Path outsideFile;

    private final StaticResourceController controller = new StaticResourceController();

    /** 带 context-path 的 MockMvc（与生产配置 application.yml 的 /api 保持一致） */
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .build();

    @BeforeAll
    static void setUpPreviewFiles() throws Exception {
        previewDir = Path.of(AppConstant.getCodeOutputRootDir(), DEPLOY_KEY);
        Files.createDirectories(Path.of(previewDir.toString(), "assets"));

        Files.writeString(Path.of(previewDir.toString(), "index.html"),
                "<html><body>preview-root</body></html>", StandardCharsets.UTF_8);
        Files.writeString(Path.of(previewDir.toString(), "style.css"),
                "body{margin:0}", StandardCharsets.UTF_8);
        Files.writeString(Path.of(previewDir.toString(), "assets", "app.js"),
                "console.log('ok')", StandardCharsets.UTF_8);

        // 越权目标放在预览根目录的上一级（tmp/ 下），
        // 而不是根目录内 —— 放在根目录内属于合法可访问范围，测不出穿越防护
        outsideDir = Path.of(AppConstant.getCodeOutputRootDir()).getParent();
        outsideFile = Path.of(outsideDir.toString(), "outside-secret-for-test.txt");
        Files.writeString(outsideFile, "should-not-be-served", StandardCharsets.UTF_8);
    }

    @AfterAll
    static void cleanUpPreviewFiles() throws Exception {
        deleteRecursively(previewDir.toFile());
        Files.deleteIfExists(outsideFile);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    // ==================================================================
    // 一、路径解析逻辑（核心回归点）—— 直接单测 resolveResourcePath
    // ==================================================================

    @Nested
    @DisplayName("资源路径解析（无 context-path）")
    class WithoutContextPath {

        private HttpServletRequest requestFor(String uri) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
            req.setRequestURI(uri);
            return req;
        }

        @Test
        @DisplayName("尾部斜杠应解析为 '/'，最终由控制器映射到 index.html")
        void trailingSlash() {
            String path = controller.resolveResourcePath(DEPLOY_KEY,
                    requestFor("/static/" + DEPLOY_KEY + "/"));
            assertThat(path).isEqualTo("/");
        }

        @Test
        @DisplayName("无尾部斜杠应解析为空串（同样映射到 index.html）")
        void noTrailingSlash() {
            String path = controller.resolveResourcePath(DEPLOY_KEY,
                    requestFor("/static/" + DEPLOY_KEY));
            assertThat(path).isEmpty();
        }

        @Test
        @DisplayName("双斜杠应能容错识别为纯斜杠")
        void doubleSlash() {
            String path = controller.resolveResourcePath(DEPLOY_KEY,
                    requestFor("/static/" + DEPLOY_KEY + "//"));
            assertThat(path).isEqualTo("//");
            assertThat(path.chars().allMatch(c -> c == '/')).isTrue();
        }

        @Test
        @DisplayName("子资源路径应完整保留")
        void nestedResource() {
            String path = controller.resolveResourcePath(DEPLOY_KEY,
                    requestFor("/static/" + DEPLOY_KEY + "/assets/app.js"));
            assertThat(path).isEqualTo("/assets/app.js");
        }
    }

    @Nested
    @DisplayName("资源路径解析（带 context-path=/api）")
    class WithContextPath {

        private HttpServletRequest requestFor(String contextPath, String uri) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
            req.setContextPath(contextPath);
            req.setRequestURI(contextPath + uri);
            return req;
        }

        @Test
        @DisplayName("context-path 不应影响目录根解析（历史 bug 的直接回归点）")
        void trailingSlashWithContextPath() {
            String path = controller.resolveResourcePath(DEPLOY_KEY,
                    requestFor(CONTEXT_PATH, "/static/" + DEPLOY_KEY + "/"));
            // 关键断言：必须仍是 "/"，而不是被吃掉后的空串
            assertThat(path).isEqualTo("/");
        }

        @Test
        @DisplayName("context-path 下子资源路径同样不受影响")
        void nestedResourceWithContextPath() {
            String path = controller.resolveResourcePath(DEPLOY_KEY,
                    requestFor(CONTEXT_PATH, "/static/" + DEPLOY_KEY + "/assets/app.js"));
            assertThat(path).isEqualTo("/assets/app.js");
        }

        @Test
        @DisplayName("URI 中不含 deployKey 时返回 null")
        void missingDeployKey() {
            String path = controller.resolveResourcePath(DEPLOY_KEY,
                    requestFor(CONTEXT_PATH, "/static/other_key/index.html"));
            assertThat(path).isNull();
        }
    }

    // ==================================================================
    // 二、端到端 HTTP 行为 —— 通过 MockMvc 验证状态码与内容
    // ==================================================================

    @Nested
    @DisplayName("HTTP 响应行为")
    class HttpBehavior {

        @Test
        @DisplayName("目录根访问（尾斜杠）应返回 index.html，且不发生重定向")
        void shouldServeIndexForTrailingSlash() throws Exception {
            mockMvc.perform(get("/static/" + DEPLOY_KEY + "/"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Location", org.hamcrest.Matchers.nullValue()))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("preview-root")));
        }

        @Test
        @DisplayName("目录根访问（无尾斜杠）应返回 index.html")
        void shouldServeIndexWithoutTrailingSlash() throws Exception {
            mockMvc.perform(get("/static/" + DEPLOY_KEY))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("preview-root")));
        }

        @Test
        @DisplayName("双斜杠应容错返回 index.html，而不是 500")
        void shouldTolerateDoubleSlash() throws Exception {
            mockMvc.perform(get("/static/" + DEPLOY_KEY + "//"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("preview-root")));
        }

        @Test
        @DisplayName("显式 index.html 应正常返回")
        void shouldServeExplicitIndex() throws Exception {
            mockMvc.perform(get("/static/" + DEPLOY_KEY + "/index.html"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("preview-root")));
        }

        @Test
        @DisplayName("子目录资源应返回正确 Content-Type")
        void shouldServeNestedWithCorrectContentType() throws Exception {
            mockMvc.perform(get("/static/" + DEPLOY_KEY + "/style.css"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/css"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("margin:0")));

            mockMvc.perform(get("/static/" + DEPLOY_KEY + "/assets/app.js"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/javascript"));
        }

        @Test
        @DisplayName("资源不存在应返回 404")
        void shouldReturn404ForMissing() throws Exception {
            mockMvc.perform(get("/static/" + DEPLOY_KEY + "/not-exist.html"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("响应应带 no-store 缓存头")
        void shouldSetNoStoreHeader() throws Exception {
            mockMvc.perform(get("/static/" + DEPLOY_KEY + "/index.html"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
    }

    // ==================================================================
    // 三、安全 —— 路径穿越防护
    // ==================================================================

    @Nested
    @DisplayName("路径穿越防护")
    class PathTraversal {

        private static final String SECRET_FILE = "outside-secret-for-test.txt";

        @Test
        @DisplayName("越权访问预览根目录之外的文件应被拒绝，绝不能返回其内容")
        void shouldBlockTraversalToOutsideFile() throws Exception {
            // 使用 URL 编码的 ../，避免 MockMvc 在分发前对 URI 做规范化，
            // 从而让请求真正抵达控制器，检验 canonicalPath 校验是否生效
            mockMvc.perform(get("/static/" + DEPLOY_KEY + "/%2e%2e/" + SECRET_FILE))
                    .andExpect(status().is4xxClientError())
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("should-not-be-served"))));
        }

        @Test
        @DisplayName("多重越权（编码形式）同样应被拒绝")
        void shouldBlockDeepTraversal() throws Exception {
            mockMvc.perform(get("/static/" + DEPLOY_KEY + "/%2e%2e%2f%2e%2e/" + SECRET_FILE))
                    .andExpect(status().is4xxClientError())
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("should-not-be-served"))));
        }

        @Test
        @DisplayName("URI 被规范化后绝不能泄露预览根目录之外的文件内容")
        void shouldNotLeakOutsideFileWhenNormalized() throws Exception {
            // MockMvc 会把 ../ 规范化掉，此时路径里不再含原 deployKey。
            // 唯一必须保证的是：根目录之外的文件内容绝不能被返回。
            mockMvc.perform(get("/static/" + DEPLOY_KEY + "/../" + SECRET_FILE))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("should-not-be-served"))));
        }
    }

    // ==================================================================
    // 四、内容类型映射
    // ==================================================================

    @Test
    @DisplayName("常见扩展名应映射到正确 Content-Type")
    void shouldMapContentTypes() {
        ResponseEntity<org.springframework.core.io.Resource> resp =
                new StaticResourceController().serveStaticResource(DEPLOY_KEY, requestWithUri("/static/" + DEPLOY_KEY + "/style.css"));
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getContentType().toString()).startsWith("text/css");
    }

    @Nested
    @DisplayName("Content-Type 映射（Vue 构建产物必需类型）")
    class ContentTypeMapping {

        /**
         * 历史问题：早期只覆盖 html/css/js/png/jpg，其余一律 application/octet-stream。
         * Vue 构建产物带的 svg/woff2/json 一旦被标为 octet-stream，浏览器会拒绝按类型加载，
         * 表现为字体不生效、SVG 图挂掉、sourcemap 报错。
         */
        @Test
        @DisplayName("构建产物常见资源类型必须被正确识别")
        void shouldMapAssetTypes() {
            StaticResourceController c = new StaticResourceController();
            assertThat(c.getContentTypeWithCharset("/a/index.html")).startsWith("text/html");
            assertThat(c.getContentTypeWithCharset("/a/assets/index-abc.js")).startsWith("application/javascript");
            assertThat(c.getContentTypeWithCharset("/a/assets/index-abc.css")).startsWith("text/css");
            assertThat(c.getContentTypeWithCharset("/a/logo.svg")).isEqualTo("image/svg+xml; charset=UTF-8");
            assertThat(c.getContentTypeWithCharset("/a/font.woff2")).isEqualTo("font/woff2");
            assertThat(c.getContentTypeWithCharset("/a/font.woff")).isEqualTo("font/woff");
            assertThat(c.getContentTypeWithCharset("/a/data.json")).startsWith("application/json");
            assertThat(c.getContentTypeWithCharset("/a/app.js.map")).startsWith("application/json");
            assertThat(c.getContentTypeWithCharset("/a/favicon.ico")).isEqualTo("image/x-icon");
            assertThat(c.getContentTypeWithCharset("/a/pic.webp")).isEqualTo("image/webp");
        }

        @Test
        @DisplayName("扩展名大小写不敏感")
        void shouldBeCaseInsensitive() {
            StaticResourceController c = new StaticResourceController();
            assertThat(c.getContentTypeWithCharset("/a/LOGO.SVG")).isEqualTo("image/svg+xml; charset=UTF-8");
            assertThat(c.getContentTypeWithCharset("/a/INDEX.HTML")).startsWith("text/html");
        }

        @Test
        @DisplayName("未知扩展名回退为 octet-stream，不得抛异常")
        void shouldFallbackForUnknown() {
            StaticResourceController c = new StaticResourceController();
            assertThat(c.getContentTypeWithCharset("/a/file.unknownext")).isEqualTo("application/octet-stream");
            assertThat(c.getContentTypeWithCharset("/a/noextension")).isEqualTo("application/octet-stream");
        }
    }

    private MockHttpServletRequest requestWithUri(String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        return req;
    }
}
