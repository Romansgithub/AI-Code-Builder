package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 构建自愈相关配置。
 *
 * <p>自愈循环是「用 AI 修 AI」的过程，每次重试都消耗 token 与用户等待时间，
 * 因此重试上限必须可控，且要有明确的止损条件。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.self-healing")
public class SelfHealingProperties {

    /**
     * 总开关。
     */
    private boolean enabled = true;

    /**
     * 最大修复轮数（不含首次生成）。设为 0 等价于关闭自愈。
     *
     * <p>默认 2：经验上多数构建错误一轮就能修好，第二轮覆盖「改错了」的情况。
     * 设得再高收益递减，用户等待时间却线性增长。</p>
     */
    private int maxAttempts = 2;

    /**
     * 喂给模型的错误日志字符上限，避免提示词过长导致 token 爆炸。
     */
    private int maxErrorLength = 3000;

    @PostConstruct
    public void init() {
        if (maxAttempts < 0) {
            log.warn("maxAttempts 不能为负，已重置为 0（关闭自愈）");
            maxAttempts = 0;
        }
        if (maxErrorLength <= 0) {
            maxErrorLength = 3000;
        }
        log.info("构建自愈配置：enabled={}, maxAttempts={}, maxErrorLength={}",
                enabled, maxAttempts, maxErrorLength);
    }

    /**
     * 自愈是否实际生效。
     */
    public boolean isActive() {
        return enabled && maxAttempts > 0;
    }
}
