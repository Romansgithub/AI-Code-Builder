package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 评测跑批配置。
 *
 * <p><b>为什么默认关闭</b>：跑批会真实调用 AI 并执行 npm 构建，
 * 一轮 8 条用例的成本和耗时都不小（Vue 用例还要装依赖）。
 * 它是个「想用的时候手动开」的能力，不该随服务启动就悄悄跑起来。</p>
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
     * 单条用例的最长等待时间（秒）。
     *
     * <p>Vue 用例要跑 npm install + build + 可能的自愈重试，给足时间；
     * 超时按失败记录，不让一条卡住的用例拖垮整轮跑批。</p>
     */
    private int caseTimeoutSeconds = 600;

    /**
     * 跑批产物子目录（相对 code-output-dir）。
     *
     * <p>它与用户真实产物隔开，避免评测产生的应用混进用户的 /app 列表。</p>
     *
     * <p><b>实现方式</b>：跑批期间整个「产物根」会被临时切到
     * {@code code-output-dir/{outputSubDir}}，生成侧照常按 {@code {codeType}_{appId}}
     * 在它下面建目录。也就是说这个值只是换根，<b>不参与目录命名</b> ——
     * 命名始终只有生产那一套，否则「写盘的」和「读盘的」就会各算各的、再次漂移
     * （2026-09-20 评测 8 条全失败正是这么来的）。</p>
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
