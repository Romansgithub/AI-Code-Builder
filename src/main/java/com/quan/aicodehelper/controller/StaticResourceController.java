package com.quan.aicodehelper.controller;

import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.constant.AppConstant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/static")
public class StaticResourceController {

    /**
     * 预览根目录。
     *
     * <p>不做成 static final：路径由 StorageProperties 在启动时回填，
     * 类加载期固化会导致注入的配置失效（同时也不利于单测替换）。</p>
     */
    private String previewRootDir() {
        return AppConstant.getCodeOutputRootDir();
    }

    @GetMapping("/{deployKey}/**")
    public ResponseEntity<Resource> serveStaticResource(@PathVariable String deployKey, HttpServletRequest request){
        try{
            String resourcePath = resolveResourcePath(deployKey, request);
            // 统一收敛：空路径以及纯斜杠（/、//）一律视为目录根，直接映射到 index.html。
            // 注意：这里刻意不做 301 重定向 —— 旧实现重定向到 xxx// 会产生双斜杠，
            // 再次进入时既不等同于 "/" 也找不到对应文件，最终表现为 404/500。
            if (resourcePath == null) {
                return ResponseEntity.notFound().build();
            }
            if (resourcePath.isEmpty() || resourcePath.chars().allMatch(c -> c == '/'))
            {
                resourcePath = "/index.html";
            }
            String rootDir = previewRootDir();
            String filePath = rootDir + "/" + deployKey + resourcePath;
            File file = new File(filePath);
            File canonicalRoot = new File(rootDir).getCanonicalFile();
            if (!file.getCanonicalPath().startsWith(canonicalRoot.getCanonicalPath() + File.separator))
            {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            if (!file.exists() || file.isDirectory())
            {
                return ResponseEntity.notFound().build();
            }
            Resource resource = new FileSystemResource(file);
            return ResponseEntity.ok()
                    .header("Content-Type", getContentTypeWithCharset(filePath))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(resource);

        }catch (Exception e){
            log.warn("静态资源读取失败: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * 从请求中解析出「相对于 deployKey 目录」的资源路径。
     *
     * <p>历史坑：旧实现用 {@code getRequestURI().substring(("/static/" + deployKey).length())}
     * 计算偏移量，一旦中间插入了 context-path 处理（或 context-path 长度变化），
     * 偏移量就会与实际不符，把路径首字符吃掉，导致 index.html 分支失效。
     * 因此这里改为「按 deployKey 的实际出现位置切割」，不依赖任何固定长度。</p>
     *
     * @return 资源相对路径（形如 {@code "/index.html"}、{@code "/assets/app.js"}）；
     *         若 URI 中不含 deployKey（异常请求）返回 {@code null}
     */
    String resolveResourcePath(String deployKey, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StrUtil.isNotBlank(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        uri = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        int keyIndex = uri.indexOf(deployKey);
        if (keyIndex < 0) {
            return null;
        }
        return uri.substring(keyIndex + deployKey.length());
    }

    /**
     * 根据扩展名返回 Content-Type。
     *
     * <p>历史问题：早期只覆盖 html/css/js/png/jpg，其余一律 application/octet-stream。
     * Vue 构建产物里的 {@code .svg}、{@code .woff2}、{@code .json} 等一旦被标为
     * octet-stream，浏览器会拒绝按类型加载（字体不显示、SVG 挂图），必须补全。</p>
     *
     * <p>文本类统一声明 charset=UTF-8，避免中文页面乱码。</p>
     */
    String getContentTypeWithCharset(String filePath) {
        String lower = filePath.toLowerCase();
        int dot = lower.lastIndexOf('.');
        String ext = dot < 0 ? "" : lower.substring(dot);
        switch (ext) {
            case ".html":
            case ".htm":
                return "text/html; charset=UTF-8";
            case ".css":
                return "text/css; charset=UTF-8";
            case ".js":
            case ".mjs":
                return "application/javascript; charset=UTF-8";
            case ".json":
                return "application/json; charset=UTF-8";
            case ".txt":
            case ".md":
                return "text/plain; charset=UTF-8";
            case ".xml":
                return "application/xml; charset=UTF-8";
            case ".svg":
                return "image/svg+xml; charset=UTF-8";
            case ".png":
                return "image/png";
            case ".jpg":
            case ".jpeg":
                return "image/jpeg";
            case ".gif":
                return "image/gif";
            case ".webp":
                return "image/webp";
            case ".ico":
                return "image/x-icon";
            case ".woff":
                return "font/woff";
            case ".woff2":
                return "font/woff2";
            case ".ttf":
                return "font/ttf";
            case ".otf":
                return "font/otf";
            case ".eot":
                return "application/vnd.ms-fontobject";
            case ".mp4":
                return "video/mp4";
            case ".webm":
                return "video/webm";
            case ".mp3":
                return "audio/mpeg";
            case ".wasm":
                return "application/wasm";
            case ".map":
                return "application/json; charset=UTF-8";
            default:
                return "application/octet-stream";
        }
    }
}
