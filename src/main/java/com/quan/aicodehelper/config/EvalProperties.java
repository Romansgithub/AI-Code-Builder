package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评测跑批配置。默认关闭：跑批真实调用 AI 并执行 npm 构建，成本和耗时都不小，应手动开启而非随服务启动。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.eval")
public class EvalProperties {

    /**
     * 是否启用评测跑批接口。
     */
    private boolean enabled = false;

    /**
     * 评测集资源路径。
     */
    private String evalSetPath = "eval/codegen-eval-set.json";

    /**
     * 单条用例的最长等待时间（秒）。Vue 用例要跑 npm install + build 与自愈重试，给足时间；超时按失败记录。
     */
    private int caseTimeoutSeconds = 600;

    /**
     * 跑批产物子目录（相对 code-output-dir）。与用户真实产物隔开，避免评测应用混进用户 /app 列表；
     * 跑批期整个产物根临时切到此处，命名仍只用生产那套。
     */
    private String outputSubDir = "eval-run";

    @PostConstruct
    public void init() {
        if (caseTimeoutSeconds <= 0) {
            log.warn("评测单例超时配置非法（{}），回退为 600 秒", caseTimeoutSeconds);
            caseTimeoutSeconds = 600;
        }
        if (evalSetPath == null || evalSetPath.isBlank()) {
            evalSetPath = "eval/codegen-eval-set.json";
        }
        if (outputSubDir == null || outputSubDir.isBlank()) {
            outputSubDir = "eval-run";
        }
        log.info("评测跑批配置：enabled={}, 超时={}s, 评测集={}", enabled, caseTimeoutSeconds, evalSetPath);
    }
}
