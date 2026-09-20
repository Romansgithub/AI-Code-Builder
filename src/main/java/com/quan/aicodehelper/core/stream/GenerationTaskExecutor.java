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
 * 生成任务的执行器：让「生成」脱离 HTTP 请求的生命周期。
 *
 * <p><b>为什么必须这么做</b>：原先生成流的订阅发生在 HTTP 响应组装过程中，
 * 于是连接一断，Reactor 序列就被取消，AI 调用随之中止。
 * 用户视角是「网络抖了一下，生成白跑了」；服务端视角是「用户没收到，但 token 已经烧了」。
 * 更糟的是浏览器会带着同一个 URL 自动重连，于是又跑一遍。</p>
 *
 * <p><b>现在的做法</b>：在独立虚拟线程里订阅生成流并消费到底 ——
 * 内容的组装、落盘、对话历史写入全部在里面完成，与有没有人在听无关。
 * 订阅返回的 {@link Disposable} 挂到任务现场上，供「主动放弃」场景使用，
 * 而不是供断线使用。</p>
 *
 * <p><b>为什么用虚拟线程</b>：一次 Vue 工程生成可能持续十几分钟且几乎全程在等 IO（模型返回、npm 构建）。
 * 平台线程被这样长期占用会拖垮线程池，虚拟线程则几乎零成本。</p>
 */
@Slf4j
@Component
public class GenerationTaskExecutor {

    @Resource
    private GenerationProgressRegistry registry;

    @Resource
    private SseProperties sseProperties;

    /**
     * 启动一个生成任务。
     *
     * <p>任务的终态一定会在某个出口被推进：正常结束、异常、看门狗超时三条路各有一处
     * {@code finishTask}。任何一条漏掉，前端都会挂到连接超时为止。</p>
     *
     * @param task     已抢占成功的任务现场
     * @param stream   生成流（由 service 层提供，本类不做业务决策）
     */
    public void start(GenerationTask task, Flux<String> stream) {
        Long appId = task.getAppId();
        AtomicBoolean finished = new AtomicBoolean(false);

        Thread worker = Thread.ofVirtual()
                .name("gen-task-" + appId)
                .unstarted(() -> {
                    try {
                        Disposable subscription = stream
                                // 业务层已负责落盘与历史写入，这里只做「消耗到底 + 上报终态」。
                                // 不收集内容：既省内存，也避免在响应式回调里做业务判断。
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
                        // 订阅是异步的，立刻返回；真正的等待发生在 Reactor 的调度线程上。
                        // 因此这个虚拟线程在这里就结束了，不会为每个任务常驻一个线程。
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
     * 看门狗：把跑到一半没了消息的任务收尾。
     *
     * <p>Reactor 的 timeout 只能管到「流内两次事件之间的间隔」，管不到
     * 「生成线程自己卡死或静默退出」这种情况。没有兜底的话，
     * 任务会长期停在 RUNNING，后续所有生成请求都被幂等保护挡住，
     * 用户彻底无法使用该应用 —— 这是比「报错」严重得多的事故形态。</p>
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
