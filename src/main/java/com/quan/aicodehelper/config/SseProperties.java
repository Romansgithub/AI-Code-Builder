package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SSE 生成流相关配置。
 *
 * <p><b>为什么这些值不能写死在代码里</b>：单实例开发和真实部署对这两件事的容忍度完全不同 ——
 * 本机内存充足、单个人用，缓冲开大点没关系；线上每个 appId 都留一份缓冲，
 * 上限直接决定内存水位。所以做成配置，并给非法值兜底。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.sse")
public class SseProperties {

    /**
     * 每个应用保留的进度事件条数上限（环形缓冲）。
     *
     * <p>取 200 的依据：Vue 工程生成会逐文件推送「项目已生成：xxx.vue」，
     * 一个中等规模项目约几十条，加上构建与自愈阶段的进度，200 条足够覆盖
     * 一次完整生成且留有余量，单条事件不过百来字节，200 条约几十 KB。</p>
     */
    private int bufferSize = 200;

    /**
     * 任务终态在内存中的保留时长（毫秒）。
     *
     * <p>不能任务一结束就删：用户断线后在几秒内重连，必须还能拿到
     * 「已完成 + 缓冲里的历史事件」，否则重连会看到「任务不存在」。
     * 也不能留太久，否则内存只增不减。</p>
     */
    private long taskRetentionMs = 600_000L;

    /**
     * 单个订阅连接在无事件时的心跳间隔（毫秒）。
     *
     * <p>作用是防止中间代理（Nginx 等）把长时间无数据的连接当空闲连接掐掉。
     * 心跳以 SSE 注释行下发，不产生事件，前端无需处理。</p>
     */
    private long heartbeatIntervalMs = 15_000L;

    /**
     * 单次生成的看门狗超时（分钟）。超时后即便 AI 流没有正常结束，
     * 也要把任务置为失败并通知订阅者，避免任务永久停在 RUNNING。
     */
    private int generationTimeoutMinutes = 20;

    /**
     * 单个应用同时只允许一个进行中的生成任务。
     *
     * <p>置为 false 可放开并发限制，但此时「重复生成」的防护只剩网关闭包了 ——
     * 除非明确知道自己在做什么，否则不要关。</p>
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
