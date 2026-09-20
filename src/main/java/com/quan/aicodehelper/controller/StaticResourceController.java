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
    private static final String PREVIEW_ROOT_DIR = AppConstant.CODE_OUTPUT_ROOT_DIR;

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
            String filePath = PREVIEW_ROOT_DIR + "/" + deployKey + resourcePath;
            File file = new File(filePath);
            File rootDir = new File(PREVIEW_ROOT_DIR).getCanonicalFile();
            if (!file.getCanonicalPath().startsWith(rootDir.getCanonicalPath() + File.separator))
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

    private String getContentTypeWithCharset(String filePath) {
        if (filePath.endsWith(".html")) return "text/html; charset=UTF-8";
        if (filePath.endsWith(".css")) return "text/css; charset=UTF-8";
        if (filePath.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (filePath.endsWith(".png")) return "image/png";
        if (filePath.endsWith(".jpg")) return "image/jpeg";
        return "application/octet-stream";

    }
}
