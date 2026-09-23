package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 构建自愈相关配置。自愈循环「用 AI 修 AI」每轮消耗 token 与等待，重试上限须可控且有明确止损条件。
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
     * 最大修复轮数（不含首次生成）。设为 0 等价于关闭自愈。默认 2：多数构建错误一轮就能修好，
     * 再高收益递减、用户等待却线性增长。
     */
    private int maxAttempts = 2;

    /**
     * 喂给模型的错误日志字符上限。只受 token 成本约束，不受「用户输入 ≤1000 字」护栏约束：
     * 自愈走的是 {@code AiCodeHealingService}，一个不带用户输入护栏的独立服务。
     * 曾因自愈复用生成服务，3000 与护栏的 1000 冲突，报错超过 332 字即被拒，
     * 表现为「自愈两轮但模型一次没被调用」；改动本值时先确认自愈链路没挂回护栏。
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

    public boolean isActive() {
        return enabled && maxAttempts > 0;
    }
}
