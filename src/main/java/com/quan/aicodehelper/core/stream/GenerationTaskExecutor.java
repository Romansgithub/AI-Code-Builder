package com.quan.aicodehelper.core.stream;

import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.config.SseProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 生成任务的执行器：让「生成」脱离 HTTP 请求生命周期，在独立虚拟线程里消费到底，与有无订阅者无关。
 * 用虚拟线程承接可能持续十几分钟的 IO 密集任务，避免长时间占用平台线程拖垮线程池。
 */
@Slf4j
@Component
public class GenerationTaskExecutor {

    @Resource
    private GenerationProgressRegistry registry;

    @Resource
    private SseProperties sseProperties;

    /**
     * 启动一个生成任务（task 须已抢占成功）。终态一定在某一出口被推进：
     * 正常结束、异常、看门狗超时各有一处 finishTask，漏掉任何一个前端都会挂到连接超时。
     */
    public void start(GenerationTask task, Flux<String> stream) {
        Long appId = task.getAppId();
        AtomicBoolean finished = new AtomicBoolean(false);

        Thread worker = Thread.ofVirtual()
                .name("gen-task-" + appId)
                .unstarted(() -> {
                    try {
                        Disposable subscription = stream
                                // 业务层负责落盘；这里只消耗到底并上报终态，不收集内容（省内存，也不在响应式回调里做业务判断）
                                .doOnError(e -> {
                                    log.error("应用 {} 的生成流异常：{}", appId, e.getMessage(), e);
                                    if (finished.compareAndSet(false, true)) {
                                        registry.finishTask(appId, describe(e));
                                    }
                                })
                                .subscribe(
                                        chunk -> { /* 内容由业务层落盘，此处只推动流前进 */ },
                                        e -> { /* 已由 doOnError 处理终态 */ },
                                        () -> {
                                            log.info("应用 {} 的生成任务完成", appId);
                                            if (finished.compareAndSet(false, true)) {
                                                registry.finishTask(appId, null);
                                            }
                                        });
                        task.bindSubscription(subscription);
                        // 订阅是异步的，立刻返回，真正的等待在 Reactor 的调度线程上，故本虚拟线程随即结束
                    } catch (Exception e) {
                        log.error("应用 {} 启动生成任务失败：{}", appId, e.getMessage(), e);
                        if (finished.compareAndSet(false, true)) {
                            registry.finishTask(appId, describe(e));
                        }
                    }
                });
        worker.start();
    }

    /**
     * 看门狗：收尾跑到一半没消息的任务。Reactor 的 timeout 管不到「生成线程卡死或静默退出」，
     * 漏掉兜底会让任务长期停在 RUNNING，后续生成全被幂等保护挡住，用户彻底无法使用。
     */
    @Scheduled(fixedDelay = 60_000L)
    public void watchdogCheck() {
        long timeoutMs = Duration.ofMinutes(sseProperties.getGenerationTimeoutMinutes()).toMillis();
        long now = System.currentTimeMillis();
        for (Long appId : registry.runningTaskIds()) {
            GenerationTask task = registry.getTask(appId);
            if (task == null || !task.isRunning()) {
                continue;
            }
            if (now - task.getCreatedAt() > timeoutMs) {
                log.warn("应用 {} 的生成任务已超过 {} 分钟仍未结束，按超时处理",
                        appId, sseProperties.getGenerationTimeoutMinutes());
                task.cancelSubscription();
                registry.finishTask(appId, "生成超时，请稍后重试");
            }
        }
    }

    private String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
