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
 * 生成任务的订阅中心：负责「任务现场」与「事件下发」两件事，但<b>不负责生成本身</b>。
 *
 * <p><b>它解决什么问题</b>：原先这个类只维护 {@code appId -> Sink}，也就是
 * 「有人连着我才能说话」。这带来三个真实故障：</p>
 * <ol>
 *   <li><b>重连即重复生成</b>：进度与内容绑在同一个 GET 请求上，浏览器断线后自动重放请求，
 *       服务端就会老老实实再生成一遍 —— 重复烧 token、重复写文件。</li>
 *   <li><b>断线期间的进度永久丢失</b>：{@code emit} 时没有订阅者，事件直接消失，
 *       用户重连后看到的是断片进度。</li>
 *   <li><b>新旧连接互相误杀</b>：{@code register} 用 {@code put} 覆盖旧 Sink，
 *       旧连接结束时 {@code complete(appId)} 会把<b>新连接</b>的 Sink 一并移除。</li>
 * </ol>
 *
 * <p><b>现在的分工</b>：任务现场（{@link GenerationTask}）记录「有没有在跑、跑到哪一步」，
 * 事件缓冲（{@link GenerationEventStore}）记录「发生过什么」，Sink 只做一件事 ——
 * 把「有变化了」这个信号叫醒正在监听的连接。三者分离之后，
 * 连接断开只是「没人听」，任务照跑、事件照记；重连则先补齐历史事件，再接着听新的。</p>
 *
 * <p><b>仍然存在的边界</b>：任务现场与事件缓冲都在 JVM 内存中，因此这套机制
 * 只在单实例部署下成立。多实例需要把两者搬到 Redis（Pub/Sub + Stream），
 * 本类是唯一需要改动的点。</p>
 */
@Slf4j
@Component
public class GenerationProgressRegistry {

    /**
     * 每个应用的当前任务。值为 null 表示该应用当前没有任务现场。
     */
    private final Map<Long, AtomicReference<GenerationTask>> tasks = new ConcurrentHashMap<>();

    @Resource
    private GenerationEventStore eventStore;

    @Resource
    private SseProperties sseProperties;

    // ==================================================================
    // 任务现场
    // ==================================================================

    /**
     * 抢占式登记一个新任务。
     *
     * <p>返回 null 表示该应用已有进行中的任务 —— 这正是「重复生成」防护的关键：
     * 浏览器自动重连、用户狂点按钮、前端重试逻辑同时触发时，
     * 只有一个请求能真正启动生成，其余请求只能去订阅已有任务。</p>
     *
     * @param appId 应用 ID
     * @return 成功抢占时返回新任务，已被占用时返回 null
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

    /**
     * 取得当前任务；不存在或已被回收时返回 null。
     */
    public GenerationTask getTask(Long appId) {
        if (appId == null) {
            return null;
        }
        AtomicReference<GenerationTask> holder = tasks.get(appId);
        return holder == null ? null : holder.get();
    }

    /**
     * 把任务推入终态并下发终止事件。
     *
     * <p>终止事件（done / error）只下发一次：一个应用可能同时有多个订阅者
     * （用户多开标签页），若每个订阅者都各推一条，前端的「生成结束」回调会被触发多次，
     * 表现为预览反复刷新。因此用 {@link GenerationTask#tryMarkTerminated()} 抢锁。</p>
     *
     * @param appId       应用 ID
     * @param failure     失败原因；为 null 表示正常完成
     * @return true 表示本次调用成功推进了终态，false 表示任务已处于终态或无任务
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
        // 终止事件必须进缓冲：用户可能正是在这一瞬间掉线，
        // 只要没进缓冲，重连后就永远等不到「结束」信号，只能靠超时收场。
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

    // ==================================================================
    // 事件下发
    // ==================================================================

    /**
     * 推送一条进度消息。
     *
     * <p>语义已从「推给当前订阅者」变成「记进事件流」：无论此刻有没有人在听，
     * 事件都会进入缓冲，重连时可补发。这正是断线重连能成立的前提。</p>
     */
    public void emit(Long appId, String message) {
        if (appId == null) {
            return;
        }
        GenerationEvent recorded = eventStore.append(appId, "progress",
                JSONUtil.toJsonStr(Map.of("d", message)));
        // 叫醒正在监听的连接；没人监听（或事件已被缓冲淘汰）时静默跳过。
        // 唤醒信号带上真实 id：订阅侧的「只转发水位之后的事件」依赖它做去重
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
     * 订阅一个应用的生成事件流。
     *
     * <p>流程是「先补发、后实时」：</p>
     * <ol>
     *   <li>记下当前水位 {@code watermark}；</li>
     *   <li>补发 {@code lastEventId + 1 .. watermark} 区间内的事件；</li>
     *   <li>之后只转发 id 大于水位的实时事件，避免重复下发。</li>
     * </ol>
     * <p>先记水位再读取缓冲，顺序不能反：反过来的话，
     * 两步之间产生的新事件既不在补发结果里、id 又小于水位被实时链路过滤掉，
     * 会凭空丢一条。反过来则最坏情况是同一事件下发两次，而事件重复是可容忍的。</p>
     *
     * @param appId       应用 ID
     * @param lastEventId 前端已收到的最后一个事件 id，0 表示从头订阅
     * @return SSE 事件流；任务不存在时返回一条显式错误事件
     */
    public Flux<ServerSentEvent<String>> subscribe(Long appId, long lastEventId) {
        GenerationTask task = getTask(appId);
        if (task == null) {
            // 显式告知「没有可订阅的任务」，而不是给一条永远静默的连接 ——
            // 后者在前端表现为无限转圈，用户无法判断该刷新还是该等待
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
     * 任务已结束时的收尾事件。
     *
     * <p>补发缓冲里可能已经包含过 done/error（如果它是在断线期间产生的），
     * 但重复一条终止事件比缺失一条安全得多：前端对终止事件做了幂等处理，
     * 而缺了它就只能等超时。</p>
     */
    private Flux<ServerSentEvent<String>> terminalFrom(GenerationTask task) {
        if (task.getStatus() == GenerationTaskStatusEnum.COMPLETED) {
            return Flux.just(sse("done", ""));
        }
        return Flux.just(sse("error", JSONUtil.toJsonStr(
                Map.of("error", task.getFailureReason() == null ? "生成失败" : task.getFailureReason()))));
    }

    /**
     * 回收任务现场与事件缓冲。
     *
     * <p>由定时任务在保留期到期后调用。任务结束时不能立刻回收 ——
     * 用户很可能正卡在重连的那一秒，此刻清掉会让重连直接收到「任务不存在」。</p>
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

    /**
     * 周期性清理过期任务现场与事件缓冲。
     *
     * <p>没有这个清理，内存只增不减：每个被访问过的应用都会永久留下一条缓冲。</p>
     */
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

    /**
     * 当前登记的应用数量，用于观测。
     */
    public int trackedTaskCount() {
        return tasks.size();
    }

    /**
     * 仍在进行中的应用 ID，供看门狗扫描。
     */
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

    // ==================================================================
    // 内部工具
    // ==================================================================

    /**
     * 事件 → SSE。{@code data} 直接取用缓冲里的原文，不做二次包装。
     *
     * <p>这里踩过一次坑：缓冲里存的已经是 {@code {"d":"..."}} 这样的 JSON 串，
     * 若下发时再套一层 {@code Map.of("d", ...)}，前端拿到的就是
     * {@code {"d":"{\"d\":\"...\"}"}}，解析后进度会显示成一堆转义字符。
     * 加壳只应发生一次，位置就是 {@link #emit}。</p>
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
