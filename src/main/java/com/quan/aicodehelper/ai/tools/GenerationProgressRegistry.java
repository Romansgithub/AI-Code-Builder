package com.quan.aicodehelper.ai.tools;

import cn.hutool.json.JSONUtil;
import com.quan.aicodehelper.config.SseProperties;
import com.quan.aicodehelper.core.stream.GenerationEvent;
import com.quan.aicodehelper.core.stream.GenerationEventStore;
import com.quan.aicodehelper.core.stream.GenerationTask;
import com.quan.aicodehelper.core.stream.GenerationTaskStatusEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 生成任务的订阅中心：维护「任务现场」与「事件下发」，但不负责生成本身。
 * <p>把 Sink 从「唯一真相」降级为「叫醒信号」，是为了解决三个真实故障：重连触发重复生成、断线期间进度永久丢失、
 * 新旧连接的 Sink 互相误杀。{@link GenerationTask} 记任务进展，{@link GenerationEventStore} 记事件，
 * 断开只是没人听，任务照跑；重连先补齐历史事件再听新的。
 * <p>任务现场与事件缓冲都在 JVM 内存，本机制只在单实例部署下成立；多实例需搬去 Redis，本类是唯一改动点。
 */
@Slf4j
@Component
public class GenerationProgressRegistry {

    /** 每个应用的当前任务；值为 null 表示该应用当前没有任务现场。 */
    private final Map<Long, AtomicReference<GenerationTask>> tasks = new ConcurrentHashMap<>();

    @Resource
    private GenerationEventStore eventStore;

    @Resource
    private SseProperties sseProperties;

    /**
     * 抢占式登记新任务。重连 / 狂点 / 前端重试同时触发时，只有一个请求真正启动生成，其余去订阅已有任务。
     * @return 成功抢占返回新任务，该应用已有进行中任务时返回 null
     */
    public GenerationTask startTask(Long appId) {
        if (appId == null) {
            return null;
        }
        GenerationTask candidate = new GenerationTask(appId);
        AtomicReference<GenerationTask> holder = tasks.computeIfAbsent(appId, k -> new AtomicReference<>());
        if (!candidate.casReplace(holder)) {
            return null;
        }
        return candidate;
    }

    /** 取得当前任务；不存在或已被回收时返回 null。 */
    public GenerationTask getTask(Long appId) {
        if (appId == null) {
            return null;
        }
        AtomicReference<GenerationTask> holder = tasks.get(appId);
        return holder == null ? null : holder.get();
    }

    /**
     * 把任务推入终态并下发终止事件。终止事件只下发一次：多标签页会产生多个订阅者，
     * 各推一条会让前端「生成结束」回调触发多次、预览反复刷新，故用 {@link GenerationTask#tryMarkTerminated()} 抢锁。
     * @param failure 失败原因；为 null 表示正常完成
     * @return true 表示本次成功推进了终态，false 表示任务已在终态或无任务
     */
    public boolean finishTask(Long appId, String failure) {
        GenerationTask task = getTask(appId);
        if (task == null) {
            return false;
        }
        boolean advanced = failure == null ? task.markCompleted() : task.markFailed(failure);
        if (!advanced) {
            return false;
        }
        // 终止事件必须进缓冲：用户可能正在这一瞬间掉线，没进缓冲则重连后永远等不到「结束」信号，只能靠超时收场。
        publishTerminalEvent(appId, failure, task);
        return true;
    }

    private void publishTerminalEvent(Long appId, String failure, GenerationTask task) {
        if (!task.tryMarkTerminated()) {
            return;
        }
        GenerationEvent event = failure == null
                ? eventStore.append(appId, "done", "")
                : eventStore.append(appId, "error", JSONUtil.toJsonStr(Map.of("error", failure)));
        if (event != null) {
            log.debug("应用 {} 任务终止，下发事件 {}（id={}）", appId, event.event(), event.id());
        }
    }

    /**
     * 推送一条进度消息：语义是「记进事件流」而非「推给当前订阅者」，
     * 无论有没有人听都进缓冲，重连时可补发，这是断线重连成立的前提。
     */
    public void emit(Long appId, String message) {
        if (appId == null) {
            return;
        }
        GenerationEvent recorded = eventStore.append(appId, "progress",
                JSONUtil.toJsonStr(Map.of("d", message)));
        // 叫醒监听中的连接，没人听或事件已被淘汰则静默跳过；唤醒信号带真实 id，订阅侧靠它去重
        if (recorded != null) {
            GenerationTask task = getTask(appId);
            if (task != null) {
                Sinks.Many<GenerationEvent> sink = task.currentSink();
                if (sink != null) {
                    sink.tryEmitNext(recorded);
                }
            }
        }
    }

    /**
     * 订阅一个应用的生成事件流：先补发历史，再转发实时的。
     * <p>必须先记下水位再读缓冲，顺序不能反：反过来的话，两步之间产生的新事件既不在补发结果里、
     * id 又小于水位会被实时链路过滤掉，会凭空丢一条；反过来最坏只是同一事件下发两次，而重复可容忍。</p>
     * @param lastEventId 前端已收到的最后一个事件 id，0 表示从头订阅
     * @return 任务不存在时返回一条显式错误事件而非静默连接
     */
    public Flux<ServerSentEvent<String>> subscribe(Long appId, long lastEventId) {
        GenerationTask task = getTask(appId);
        if (task == null) {
            // 显式告知「没有可订阅的任务」，而非给一条永远静默的连接（后者前端无限转圈）
            return Flux.just(sse("business-error",
                    JSONUtil.toJsonStr(Map.of("message", "没有找到进行中的生成任务，请重新发起生成"))));
        }

        long watermark = eventStore.currentMaxId(appId);
        List<GenerationEvent> backlog = eventStore.since(appId, lastEventId);
        long lostBefore = eventStore.lostBefore(appId);
        boolean hasGap = lastEventId > 0 && lastEventId < lostBefore - 1;

        Sinks.Many<GenerationEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
        task.bindSink(sink);

        Flux<ServerSentEvent<String>> live = sink.asFlux()
                .filter(e -> e.id() > watermark)
                .map(this::toSse);

        Flux<ServerSentEvent<String>> replay = Flux.fromIterable(backlog).map(this::toSse);

        Flux<ServerSentEvent<String>> head = hasGap
                ? Flux.just(sse("progress", JSONUtil.toJsonStr(Map.of(
                        "d", "部分早期进度已过期，以下为最新进度"))))
                : Flux.empty();

        // 任务已结束：补发完历史事件后立即收尾，不能让连接挂着等一个永不到来的 done
        Flux<ServerSentEvent<String>> tail = task.isRunning()
                ? Flux.empty()
                : terminalFrom(task);

        return head
                .concatWith(replay)
                .concatWith(live)
                .concatWith(tail)
                .timeout(Duration.ofMinutes(sseProperties.getGenerationTimeoutMinutes() + 5L),
                        Flux.just(sse("error", JSONUtil.toJsonStr(Map.of("error", "订阅超时，请刷新页面查看结果")))))
                .doFinally(sig -> task.clearSink(sink));
    }

    /**
     * 任务已结束时的收尾事件。缓冲里可能已含 done/error（断线期间产生），
     * 但重复一条比缺失安全：前端对终止事件幂等，缺了只能等超时。
     */
    private Flux<ServerSentEvent<String>> terminalFrom(GenerationTask task) {
        if (task.getStatus() == GenerationTaskStatusEnum.COMPLETED) {
            return Flux.just(sse("done", ""));
        }
        return Flux.just(sse("error", JSONUtil.toJsonStr(
                Map.of("error", task.getFailureReason() == null ? "生成失败" : task.getFailureReason()))));
    }

    /**
     * 回收任务现场与事件缓冲，由定时任务在保留期到期后调用。
     * <p>任务结束时不能立刻回收：用户很可能正卡在重连的那一秒，此刻清掉会让重连直接收到「任务不存在」。</p>
     */
    public void evictTask(Long appId) {
        AtomicReference<GenerationTask> holder = tasks.get(appId);
        if (holder == null) {
            return;
        }
        GenerationTask task = holder.get();
        if (task != null && task.isRunning()) {
            // 还在跑的任务不回收：说明生成线程异常退出了，留给看门狗处理
            return;
        }
        if (tasks.remove(appId, holder)) {
            eventStore.evict(appId);
            log.debug("回收应用 {} 的生成任务现场与事件缓冲", appId);
        }
    }

    /** 周期性清理过期任务现场与事件缓冲；不清理则内存只增不减，每个访问过的应用都永久留缓冲。 */
    @Scheduled(fixedDelay = 60_000L)
    public void purgeExpiredTasks() {
        long deadline = System.currentTimeMillis() - sseProperties.getTaskRetentionMs();
        eventStore.purgeExpired(deadline);
        for (Long appId : tasks.keySet()) {
            GenerationTask task = getTask(appId);
            if (task != null && !task.isRunning() && task.getCreatedAt() < deadline) {
                evictTask(appId);
            }
        }
    }

    /** 当前登记的应用数量，用于观测。 */
    public int trackedTaskCount() {
        return tasks.size();
    }

    /** 仍在进行中的应用 ID，供看门狗扫描。 */
    public List<Long> runningTaskIds() {
        List<Long> running = new java.util.ArrayList<>();
        for (Long appId : tasks.keySet()) {
            GenerationTask task = getTask(appId);
            if (task != null && task.isRunning()) {
                running.add(appId);
            }
        }
        return running;
    }

    /**
     * 事件 → SSE：{@code data} 直接取缓冲里的原文，绝不能再套一层。
     * <p>缓冲里存的已经是 {@code {"d":"..."}} 这样的 JSON 串，下发时若再包一层 {@code Map.of("d", ...)}，
     * 前端会拿到 {@code {"d":"{\"d\":\"...\"}"}}，进度显示成一堆转义字符。加壳只应发生在 {@link #emit}。</p>
     */
    private ServerSentEvent<String> toSse(GenerationEvent event) {
        return ServerSentEvent.<String>builder()
                .id(String.valueOf(event.id()))
                .event(event.event())
                .data(event.data())
                .build();
    }

    private ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }
}
