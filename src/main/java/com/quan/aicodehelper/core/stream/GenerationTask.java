package com.quan.aicodehelper.core.stream;

import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 一个应用的当前生成任务现场，与连接解耦：连接只是「往现场挂一只耳朵」，来去不影响生成。
 * 新任务通过 casReplace 抢占式替换旧任务，多个并发触发只有一方能装上自己的任务。
 * 字段被生成线程与 HTTP 线程并发读写，故一律用 AtomicReference 承载快照。
 */
public class GenerationTask {

    /** 应用 ID，同时作为 AI 会话记忆 ID。 */
    private final Long appId;

    private final long createdAt = System.currentTimeMillis();

    /** 终态（COMPLETED / FAILED）一旦写入不再变化。 */
    private final AtomicReference<GenerationTaskStatusEnum> status =
            new AtomicReference<>(GenerationTaskStatusEnum.RUNNING);

    private final AtomicReference<String> failureReason = new AtomicReference<>();

    /** 连接断开时刻意不取消它，这正是「断开不打断生成」成立的关键；只有任务被替换或关停才取消。 */
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();

    /** 保证每个订阅者最多见到一次 done / error。 */
    private final AtomicReference<Boolean> terminated = new AtomicReference<>(false);

    /** 订阅连接的唤醒信号，为 null 表示没人在听；只通知「有新事件去缓冲取」，不携带事件内容。 */
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

    /** 语义上属于「主动放弃这次生成」，只应被替换/关停这类场景调用。 */
    public void cancelSubscription() {
        Disposable disposable = subscription.getAndSet(null);
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    /** 已是终态时返回 false，避免重复下发 done。 */
    public boolean markCompleted() {
        return status.compareAndSet(GenerationTaskStatusEnum.RUNNING,
                GenerationTaskStatusEnum.COMPLETED);
    }

    public boolean markFailed(String reason) {
        if (status.compareAndSet(GenerationTaskStatusEnum.RUNNING, GenerationTaskStatusEnum.FAILED)) {
            failureReason.set(reason);
            return true;
        }
        return false;
    }

    /**
     * @return false 表示已被占用，本次不应启动生成
     */
    public boolean casReplace(AtomicReference<GenerationTask> holder) {
        while (true) {
            GenerationTask current = holder.get();
            if (current != null && current.isRunning()) {
                return false;
            }
            if (holder.compareAndSet(current, this)) {
                if (current != null) {
                    current.cancelSubscription();
                }
                return true;
            }
        }
    }

    /** @return true 表示本次调用者负责下发 done/error，其余订阅者应跳过 */
    public boolean tryMarkTerminated() {
        return terminated.compareAndSet(false, true);
    }

    /** 直接覆盖旧值：同一应用允许多订阅者，但只有一个 Sink 收实时唤醒，后连者接管，早先的靠缓冲补发。 */
    public void bindSink(Sinks.Many<GenerationEvent> newSink) {
        this.sink.set(newSink);
    }

    public Sinks.Many<GenerationEvent> currentSink() {
        return sink.get();
    }

    /** 仅当传入的 Sink 仍是当前生效的那个才清理，否则会把后来者的 Sink 误删。 */
    public void clearSink(Sinks.Many<GenerationEvent> expected) {
        sink.compareAndSet(expected, null);
    }
}
