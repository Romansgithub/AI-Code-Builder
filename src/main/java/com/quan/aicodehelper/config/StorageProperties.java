package com.quan.aicodehelper.config;

import com.quan.aicodehelper.constant.AppConstant;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 存储与部署相关配置。
 *
 * <p>历史问题：这些值原先硬编码在 {@link AppConstant} 里，其中根目录用
 * {@code System.getProperty("user.dir")} 推算 —— 该值等于「JVM 进程的工作目录」，
 * 而非「项目所在目录」。IDEA 运行配置里的 Working directory 一旦不是模块根，
 * 产物就会落到意料之外的位置。改为可配置项后，默认行为保持不变（仍按 user.dir 推算），
 * 但可通过配置文件或环境变量覆盖。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /**
     * 代码生成产物根目录。可用环境变量 APP_STORAGE_CODE_OUTPUT_DIR 覆盖。
     */
    private String codeOutputDir;

    /**
     * 部署产物根目录。可用环境变量 APP_STORAGE_CODE_DEPLOY_DIR 覆盖。
     */
    private String codeDeployDir;

    /**
     * 部署访问地址前缀（含协议、主机、端口与 context-path）。
     * 可用环境变量 APP_STORAGE_DEPLOY_HOST 覆盖，如 {@code http://localhost:8080/api/static/}。
     */
    private String deployHost;

    @PostConstruct
    public void init() {
        if (codeOutputDir == null || codeOutputDir.isBlank()) {
            codeOutputDir = Paths.get(System.getProperty("user.dir"), "tmp", "code_output")
                    .toAbsolutePath().normalize().toString();
        }
        if (codeDeployDir == null || codeDeployDir.isBlank()) {
            codeDeployDir = Paths.get(System.getProperty("user.dir"), "tmp", "code_deploy")
                    .toAbsolutePath().normalize().toString();
        }
        if (deployHost == null || deployHost.isBlank()) {
            deployHost = "http://localhost:8080/api/static/";
        }
        // 统一补尾斜杠：预览页 index.html 使用相对路径 "./assets/xxx"，
        // 缺少尾斜杠时浏览器会把 deployKey 当作文件名，相对路径基准错位，静态资源全部 404。
        if (!deployHost.endsWith("/")) {
            deployHost = deployHost + "/";
        }
        // 回填到 AppConstant，供那些以 static final 字段引用路径的工具类使用
        AppConstant.Storage.apply(codeOutputDir, codeDeployDir, deployHost);
        ensureDirectories();
        log.info("存储配置初始化完成：codeOutputDir={}, codeDeployDir={}, deployHost={}",
                codeOutputDir, codeDeployDir, deployHost);
    }

    /**
     * 代码生成产物根目录（Path 形式）。
     */
    public Path codeOutputPath() {
        return Paths.get(codeOutputDir).toAbsolutePath().normalize();
    }

    /**
     * 部署产物根目录（Path 形式）。
     */
    public Path codeDeployPath() {
        return Paths.get(codeDeployDir).toAbsolutePath().normalize();
    }

    /**
     * 拼接某个应用部署后的完整访问地址。
     *
     * @param deployKey 部署标识
     * @return 形如 {@code http://localhost:8080/api/static/AbC123/}，始终带尾斜杠
     */
    public String buildDeployUrl(String deployKey) {
        return deployHost + deployKey + "/";
    }

    /**
     * 确保根目录存在（首次运行时目录通常尚未创建）。
     */
    public void ensureDirectories() {
        for (Path p : new Path[]{codeOutputPath(), codeDeployPath()}) {
            File dir = p.toFile();
            if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
                log.warn("目录创建失败：{}", dir.getAbsolutePath());
            }
        }
    }
}
