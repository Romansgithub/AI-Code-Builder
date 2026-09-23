package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大模型调用预算配置：以「发起前的调用次数」为闸门拦住失控调用。
 * 默认开启（默认关闭等于没预算）；计数为进程内、重启清零，非跨实例配额系统。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.budget")
public class LlmBudgetProperties {

    /**
     * 总开关。关闭后所有调用直接放行（等于恢复改造前的行为）。
     */
    private boolean enabled = true;

    /**
     * 进程生命周期内允许的 LLM 调用总次数上限。一次完整联调通常用不到，跑评测或反复试自愈才会撞线。
     */
    private int maxCalls = 200;

    /**
     * 用量达到该比例时打警告日志。默认 0.8，剩 20% 即提醒，而非等撞线。
     */
    private double warnRatio = 0.8;

    /**
     * 触顶时是否直接拒绝调用。设为 false 则只记录不拦截，用于只观测用量的场景。
     */
    private boolean rejectWhenExhausted = true;

    @PostConstruct
    public void init() {
        if (maxCalls <= 0) {
            log.warn("预算上限非法（{}），必须是正数，已回退为 200", maxCalls);
            maxCalls = 200;
        }
        if (warnRatio <= 0 || warnRatio >= 1) {
            log.warn("预警比例非法（{}），应在 (0,1) 区间，已回退为 0.8", warnRatio);
            warnRatio = 0.8;
        }
        if (!enabled) {
            log.warn("LLM 调用预算已关闭（app.budget.enabled=false），调用将不受次数限制");
        } else {
            log.info("LLM 调用预算：上限 {} 次，预警阈值 {}%，触顶后{}",
                    maxCalls, (int) (warnRatio * 100), rejectWhenExhausted ? "拒绝" : "仅告警");
        }
    }
}
