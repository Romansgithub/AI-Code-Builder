package com.quan.aicodehelper.core.stream;

import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个应用的「当前生成任务」现场。
 *
 * <p><b>为什么它和连接要分开</b>：原先生成流的生命周期等同于那次 HTTP 请求 ——
 * 浏览器断开，服务端的流也跟着断。这带来两个问题：一是断线期间的产出全部丢失，
 * 二是重连会重新触发一遍生成。把任务现场独立出来之后，
 * 连接只是「往这份现场上挂一只耳朵」，来去都不影响生成本身。</p>
 *
 * <p><b>原子替换</b>：新任务通过 {@link #casReplace} 抢占式替换旧任务。
 * 用 CAS 而不是先判断再赋值，是为了让两个并发的触发起请求里只有一个能装上自己的任务 ——
 * 否则会出现「两个生成都在跑，但只有后一个的现场被发现」的错乱状态。</p>
 *
 * <p><b>可见性</b>：字段被多个线程读写（生成线程写状态，HTTP 线程读状态），
 * 因此可变字段一律用 {@link AtomicReference} 承载，而不是普通字段加锁 ——
 * 读取侧只想拿个快照，不该被生成线程持锁影响。</p>
 */
public class GenerationTask {

    /**
     * 应用 ID，同时作为 AI 会话记忆 ID。
     */
    private final Long appId;

    /**
     * 任务在内存中的创建时刻，用于「保留期到期后回收」。
     */
    private final long createdAt = System.currentTimeMillis();

    /**
     * 当前状态。终态（COMPLETED / FAILED）一旦写入不再变化。
     */
    private final AtomicReference<GenerationTaskStatusEnum> status =
            new AtomicReference<>(GenerationTaskStatusEnum.RUNNING);

    /**
     * 失败原因，仅在有值时下发。
     */
    private final AtomicReference<String> failureReason = new AtomicReference<>();

    /**
     * 生成流的订阅句柄。连接断开时**不**调用它 ——
     * 那正是让「断开不打断生成」成立的关键；只有任务被替换或服务关停时才取消。
     */
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();

    /**
     * 是否已经下发过终止事件（done / error），保证每个订阅者最多见到一次。
     */
    private final AtomicReference<Boolean> terminated = new AtomicReference<>(false);

    /**
     * 当前订阅连接的唤醒信号。为 null 表示没有人在听。
     *
     * <p>它只承担「有新事件了，去缓冲里取」这一个职责，因此不携带事件内容 ——
     * 事件本体在 {@link GenerationEventStore} 里，这里只做通知。
     * 生成线程与订阅线程可能同时操作它，故用原子引用承载。</p>
     */
    private final AtomicReference<Sinks.Many<GenerationEvent>> sink = new AtomicReference<>();

    public GenerationTask(Long appId) {
        this.appId = appId;
    }

    public Long getAppId() {
        return appId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public GenerationTaskStatusEnum getStatus() {
        return status.get();
    }

    public boolean isRunning() {
        return status.get() == GenerationTaskStatusEnum.RUNNING;
    }

    public String getFailureReason() {
        return failureReason.get();
    }

    public void bindSubscription(Disposable disposable) {
        this.subscription.set(disposable);
    }

    /**
     * 取消底层生成流。
     *
     * <p>语义上属于「主动放弃这次生成」，只应被替换/关停这类场景调用。</p>
     */
    public void cancelSubscription() {
        Disposable disposable = subscription.getAndSet(null);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    /**
     * 标记为完成。已是终态时返回 false，避免重复下发 done。
     */
    public boolean markCompleted() {
        return status.compareAndSet(GenerationTaskStatusEnum.RUNNING,
                GenerationTaskStatusEnum.COMPLETED);
    }

    /**
     * 标记为失败并记录原因。已是终态时返回 false。
     */
    public boolean markFailed(String reason) {
        if (status.compareAndSet(GenerationTaskStatusEnum.RUNNING, GenerationTaskStatusEnum.FAILED)) {
            failureReason.set(reason);
            return true;
        }
        return false;
    }

    /**
     * 抢占式替换：仅当当前仍是 RUNNING 时才替换成本任务。
     *
     * @return 若旧任务为 RUNNING 返回 false（表示已被占用，本次不应启动生成）
     */
    public boolean casReplace(AtomicReference<GenerationTask> holder) {
        while (true) {
            GenerationTask current = holder.get();
            if (current != null && current.isRunning()) {
                return false;
            }
            if (holder.compareAndSet(current, this)) {
                // 替换成功：老的已经结束或不存在，只需释放它可能残留的订阅
                if (current != null) {
                    current.cancelSubscription();
                }
                return true;
            }
        }
    }

    /**
     * 尝试抢占「终止事件下发权」。
     *
     * @return true 表示本次调用者负责下发 done/error，其余订阅者应跳过
     */
    public boolean tryMarkTerminated() {
        return terminated.compareAndSet(false, true);
    }

    /**
     * 登记当前订阅连接的唤醒信号。
     *
     * <p>直接覆盖旧值而不是拒绝：同一应用允许有多个订阅者（多开标签页），
     * 但只有一个 Sink 能收到实时唤醒。这里选择「后连的接管实时通知」，
     * 早先的连接靠缓冲补发 + 轮询水位尾随，功能不受影响 ——
     * 代价是实时性略降，收益是不必为多订阅者维护一套 Sink 列表。</p>
     */
    public void bindSink(Sinks.Many<GenerationEvent> newSink) {
        this.sink.set(newSink);
    }

    /**
     * 取得当前唤醒信号，无订阅者时为 null。
     */
    public Sinks.Many<GenerationEvent> currentSink() {
        return sink.get();
    }

    /**
     * 注销唤醒信号。仅当传入的 Sink 仍是当前生效的那个才清理，
     * 否则会把后来者的 Sink 误删（这正是旧实现 {@code complete} 误杀新连接的同类问题）。
     */
    public void clearSink(Sinks.Many<GenerationEvent> expected) {
        sink.compareAndSet(expected, null);
    }
}
