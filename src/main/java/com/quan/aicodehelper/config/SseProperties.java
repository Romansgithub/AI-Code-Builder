package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SSE 生成流相关配置。单实例开发与真实部署对缓冲/保留的容忍度不同，做成配置并给非法值兜底。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.sse")
public class SseProperties {

    /**
     * 每个应用保留的进度事件条数上限（环形缓冲）。一次完整生成的进度约几十条，200 足够且留有余量。
     */
    private int bufferSize = 200;

    /**
     * 任务终态在内存中的保留时长（毫秒）。不能一结束就删：用户断线后几秒内重连要能拿到
     * 「已完成 + 缓冲里的历史事件」，否则会看到「任务不存在」；也不能留太久，否则内存只增不减。
     */
    private long taskRetentionMs = 600_000L;

    /**
     * 单个订阅连接在无事件时的心跳间隔（毫秒）。防止中间代理（Nginx 等）把长时间无数据的连接
     * 当空闲连接掐掉；心跳以 SSE 注释行下发，不产生事件，前端无需处理。
     */
    private long heartbeatIntervalMs = 15_000L;

    /**
     * 单次生成的看门狗超时（分钟）。超时后即便 AI 流没有正常结束，也要把任务置为失败并通知订阅者，
     * 避免任务永久停在 RUNNING。
     */
    private int generationTimeoutMinutes = 20;

    /**
     * 单个应用同时只允许一个进行中的生成任务。置为 false 可放开并发，但「重复生成」的防护
     * 就只剩网关闭包，除非明确知道在做什么，否则不要关。
     */
    private boolean rejectConcurrentGeneration = true;

    @PostConstruct
    public void init() {
        if (bufferSize <= 0) {
            log.warn("SSE 缓冲条数配置非法（{}），回退为 200", bufferSize);
            bufferSize = 200;
        }
        if (taskRetentionMs <= 0) {
            log.warn("SSE 任务保留时长配置非法（{}），回退为 600000ms", taskRetentionMs);
            taskRetentionMs = 600_000L;
        }
        if (heartbeatIntervalMs <= 0) {
            log.warn("SSE 心跳间隔配置非法（{}），回退为 15000ms", heartbeatIntervalMs);
            heartbeatIntervalMs = 15_000L;
        }
        if (generationTimeoutMinutes <= 0) {
            log.warn("SSE 生成超时配置非法（{}），回退为 20 分钟", generationTimeoutMinutes);
            generationTimeoutMinutes = 20;
        }
        log.info("SSE 生成流配置：bufferSize={}, taskRetentionMs={}, heartbeatIntervalMs={}, timeout={}min",
                bufferSize, taskRetentionMs, heartbeatIntervalMs, generationTimeoutMinutes);
    }
}
