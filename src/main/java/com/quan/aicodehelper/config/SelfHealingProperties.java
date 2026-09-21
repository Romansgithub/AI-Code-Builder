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
     *
     * <p><b>这个值只受 token 成本约束，不受「用户输入 ≤1000 字」那条护栏约束</b> ——
     * 自愈走的是 {@code AiCodeHealingService}，一个不带用户输入护栏的独立服务
     * （护栏针对的是聊天框，不是机器拼出来的构建报错）。</p>
     *
     * <p>之所以要写明这一点：曾经自愈复用生成服务，于是这个 3000 与护栏的 1000
     * 天然冲突 —— 报错只要超过 332 字就必然被拒，而两处配置各自看都合理、
     * 谁也不知道对方存在。<b>报错超过 332 字即失效</b>，这正是当时那个
     * 「自愈两轮但模型一次没被调用」事故的成因。改动本值时请一并确认
     * 自愈链路没有被挂回用户输入护栏。</p>
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
