package com.quan.aicodehelper.core.stream;

import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.config.SseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成任务订阅中心测试：守的是重连不重复生成、断线可补发、新旧连接互不误杀。
 */
@DisplayName("生成任务订阅中心")
class GenerationProgressRegistryTest {

    private static final Long APP_ID = 5001L;

    private GenerationProgressRegistry registry;

    @BeforeEach
    void setUp() {
        SseProperties properties = new SseProperties();
        properties.setBufferSize(50);
        properties.setTaskRetentionMs(60_000L);
        properties.setGenerationTimeoutMinutes(1);
        properties.init();

        GenerationEventStore eventStore = new GenerationEventStore();
        ReflectionTestUtils.setField(eventStore, "sseProperties", properties);

        registry = new GenerationProgressRegistry();
        ReflectionTestUtils.setField(registry, "eventStore", eventStore);
        ReflectionTestUtils.setField(registry, "sseProperties", properties);
    }

    /** 把一个订阅流跑到结束或超时，收集全部事件 */
    private List<ServerSentEvent<String>> collect(Flux<ServerSentEvent<String>> flux, long timeoutMs) {
        List<ServerSentEvent<String>> collected = new ArrayList<>();
        flux.subscribe(collected::add);
        // 订阅是异步的，给它一小段时间把已就绪的事件推下来
        waitUntil(() -> true, timeoutMs, false);
        return collected;
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs, boolean expectTrue) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                if (!expectTrue) {
                    return;
                }
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Nested
    @DisplayName("任务唯一性（防重复生成）")
    class TaskUniqueness {

        @Test
        @DisplayName("同一应用在生成进行中时，第二次抢占必须失败")
        void shouldRejectSecondStartWhileRunning() {
            GenerationTask first = registry.startTask(APP_ID);
            assertThat(first).isNotNull();

            // 模拟浏览器自动重连 / 用户连点 / 前端重试同时打进来
            GenerationTask second = registry.startTask(APP_ID);
            assertThat(second).as("已有任务进行中，第二次抢占不应成功").isNull();

            GenerationTask third = registry.startTask(APP_ID);
            assertThat(third).isNull();

            // 现场必须仍是最初那个任务，不能被覆盖
            assertThat(registry.getTask(APP_ID)).isSameAs(first);
        }

        @Test
        @DisplayName("并发抢占同一应用时，有且只有一个请求能启动生成")
        void shouldAllowOnlyOneWinnerUnderConcurrency() throws Exception {
            int threads = 16;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            Thread[] workers = new Thread[threads];

            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (registry.startTask(APP_ID) != null) {
                        winners.incrementAndGet();
                    }
                });
                workers[i].start();
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
            for (Thread w : workers) {
                w.join(10_000);
            }

            assertThat(winners.get())
                    .as("16 个并发请求里必须只有一个能真正启动生成，否则就是重复生成事故")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("上一个任务结束后，可以正常发起新的生成")
        void shouldAllowNewTaskAfterPreviousFinished() {
            GenerationTask first = registry.startTask(APP_ID);
            assertThat(first).isNotNull();
            registry.finishTask(APP_ID, null);

            GenerationTask second = registry.startTask(APP_ID);
            assertThat(second).as("旧任务已结束，不应再拦住新生成").isNotNull();
            assertThat(second).isNotSameAs(first);
            assertThat(second.isRunning()).isTrue();
        }
    }

    @Nested
    @DisplayName("断线重连补发")
    class ResumeAfterDisconnect {

        @Test
        @DisplayName("断线期间产生的事件，重连后应能按 lastEventId 补齐")
        void shouldReplayEventsProducedWhileDisconnected() {
            registry.startTask(APP_ID);
            // 假设前端已收到 1、2 号事件，随后网络断开
            registry.emit(APP_ID, "正在安装项目依赖...");
            registry.emit(APP_ID, "项目已生成：src/App.vue");
            // 这两条发生在断线期间，前端没收到
            registry.emit(APP_ID, "正在构建项目...");
            registry.emit(APP_ID, "检测到构建错误，正在自动修复（第 1/2 次）...");

            List<ServerSentEvent<String>> replayed = collect(registry.subscribe(APP_ID, 2L), 300);

            assertThat(replayed).hasSize(2);
            assertThat(replayed).extracting(ServerSentEvent::id).containsExactly("3", "4");
            assertThat(replayed).extracting(ServerSentEvent::event).containsOnly("progress");
            assertThat(replayed.get(0).data()).contains("正在构建项目");
            assertThat(replayed.get(1).data()).contains("自动修复");
        }

        @Test
        @DisplayName("首次连接（lastEventId=0）应拿到全部保留中的进度")
        void shouldReplayAllOnFirstConnect() {
            registry.startTask(APP_ID);
            registry.emit(APP_ID, "一");
            registry.emit(APP_ID, "二");
            registry.emit(APP_ID, "三");

            List<ServerSentEvent<String>> all = collect(registry.subscribe(APP_ID, 0L), 300);
            assertThat(all).extracting(ServerSentEvent::id).containsExactly("1", "2", "3");
        }

        @Test
        @DisplayName("没有错过任何事件时，补发结果为空（不重复下发）")
        void shouldNotReplayWhenNothingMissed() {
            registry.startTask(APP_ID);
            registry.emit(APP_ID, "一");
            registry.emit(APP_ID, "二");

            List<ServerSentEvent<String>> replayed = collect(registry.subscribe(APP_ID, 2L), 200);
            assertThat(replayed).isEmpty();
        }

        @Test
        @DisplayName("事件数据必须是前端可直接解析的 JSON，不得二次包装")
        void shouldNotDoubleWrapEventData() {
            registry.startTask(APP_ID);
            registry.emit(APP_ID, "项目已生成：src/main.js");

            List<ServerSentEvent<String>> events = collect(registry.subscribe(APP_ID, 0L), 300);
            assertThat(events).hasSize(1);
            String data = events.get(0).data();
            // 前端约定：progress 事件的 data 形如 {"d":"..."}，只加一层壳
            assertThat(data).startsWith("{\"d\":").contains("项目已生成");
            assertThat(data).doesNotContain("\\\"d\\\"");
        }

        @Test
        @DisplayName("订阅不存在的任务时应明确报错，而不是给一条永远静默的连接")
        void shouldReportExplicitErrorForUnknownTask() {
            List<ServerSentEvent<String>> events = collect(registry.subscribe(APP_ID, 0L), 200);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).event()).isEqualTo("business-error");
            assertThat(events.get(0).data()).contains("没有找到进行中的生成任务");
        }

        @Test
        @DisplayName("任务已结束时的重连，应补发历史事件并立即收到终止事件")
        void shouldSendTerminalEventForFinishedTask() {
            registry.startTask(APP_ID);
            registry.emit(APP_ID, "项目构建成功");
            registry.finishTask(APP_ID, null);

            List<ServerSentEvent<String>> events = collect(registry.subscribe(APP_ID, 0L), 300);

            // 先补发历史进度（id=1），再补一条 done 收尾（done 本身也是缓冲里的事件，id=2）
            assertThat(events).extracting(ServerSentEvent::event).contains("progress", "done");
            assertThat(events.stream().anyMatch(e -> "done".equals(e.event()))).isTrue();
            // 用户断线后重连打开页面，绝不能挂着等一个永不到来的 done
            assertThat(events.get(events.size() - 1).event()).isEqualTo("done");
        }

        @Test
        @DisplayName("失败的任务在重连时应收到 error 事件并带上失败原因")
        void shouldSendErrorWithReasonForFailedTask() {
            registry.startTask(APP_ID);
            registry.finishTask(APP_ID, "模型返回超时");

            List<ServerSentEvent<String>> events = collect(registry.subscribe(APP_ID, 0L), 300);
            assertThat(events).isNotEmpty();
            ServerSentEvent<String> last = events.get(events.size() - 1);
            assertThat(last.event()).isEqualTo("error");
            assertThat(last.data()).contains("模型返回超时");
        }
    }

    @Nested
    @DisplayName("连接生命周期隔离")
    class ConnectionIsolation {

        @Test
        @DisplayName("旧连接结束时不得清理新连接的唤醒通道")
        void shouldNotEvictNewSinkWhenOldConnectionCloses() {
            GenerationTask task = registry.startTask(APP_ID);

            List<ServerSentEvent<String>> oldConnection = collect(registry.subscribe(APP_ID, 0L), 100);

            List<ServerSentEvent<String>> newConnection = new ArrayList<>();
            registry.subscribe(APP_ID, 0L).subscribe(newConnection::add);
            waitUntil(() -> true, 150, false);

            // 断开期间与新连接建立后产生的进度，新连接必须能收到
            registry.emit(APP_ID, "重连后产生的进度");

            waitUntil(() -> !newConnection.isEmpty(), 1000, true);
            assertThat(newConnection)
                    .as("新连接必须能收到后续进度，不能被旧连接的清理动作误删")
                    .isNotEmpty();
            assertThat(newConnection.stream()
                    .anyMatch(e -> e.data() != null && e.data().contains("重连后产生的进度")))
                    .isTrue();
            assertThat(task.currentSink()).isNotNull();
        }

        @Test
        @DisplayName("断开连接不得中断生成任务本身")
        void shouldKeepTaskRunningAfterSubscriberLeaves() {
            GenerationTask task = registry.startTask(APP_ID);

            collect(registry.subscribe(APP_ID, 0L), 100);

            assertThat(task.isRunning())
                    .as("订阅者离开只是没人听，任务本身必须继续")
                    .isTrue();
            assertThat(registry.getTask(APP_ID)).isSameAs(task);
        }
    }

    @Nested
    @DisplayName("终态与回收")
    class TerminalStateAndEviction {

        @Test
        @DisplayName("终止事件只下发一次，重复 finish 不得重复推进")
        void shouldAdvanceTerminalStateOnlyOnce() {
            registry.startTask(APP_ID);

            assertThat(registry.finishTask(APP_ID, null)).isTrue();
            assertThat(registry.finishTask(APP_ID, null))
                    .as("已是终态，重复调用应返回 false").isFalse();
            assertThat(registry.finishTask(APP_ID, "另一个失败原因")).isFalse();

            // 直接查缓冲更稳：对结束后的订阅流做 count().block() 容易因序列收尾卡到超时
            List<ServerSentEvent<String>> events = collect(registry.subscribe(APP_ID, 0L), 200);
            long doneCount = events.stream().filter(e -> "done".equals(e.event())).count();
            assertThat(doneCount).as("终止事件重复下发会让前端预览反复刷新").isEqualTo(1);
        }

        @Test
        @DisplayName("任务失败时状态与原因都要被记录下来，供刷新页面后恢复现场")
        void shouldRecordFailureReason() {
            GenerationTask task = registry.startTask(APP_ID);
            registry.finishTask(APP_ID, "构建工具未安装");

            assertThat(task.getStatus()).isEqualTo(GenerationTaskStatusEnum.FAILED);
            assertThat(task.getFailureReason()).isEqualTo("构建工具未安装");
            assertThat(task.isRunning()).isFalse();
        }

        @Test
        @DisplayName("未结束的任务不允许被回收（防止任务现场被人为抹掉）")
        void shouldNotEvictRunningTask() {
            registry.startTask(APP_ID);
            registry.evictTask(APP_ID);

            assertThat(registry.getTask(APP_ID))
                    .as("正在跑的任务不能被回收，否则后续请求会误判为无任务而重复生成")
                    .isNotNull();
        }

        @Test
        @DisplayName("已结束且超过保留期的任务应被回收，缓冲随之释放")
        void shouldEvictExpiredFinishedTask() throws Exception {
            registry.startTask(APP_ID);
            registry.finishTask(APP_ID, null);
            assertThat(registry.trackedTaskCount()).isEqualTo(1);

            // 测试 profile 里保留期只有 1 秒
            SseProperties shortLived = new SseProperties();
            shortLived.setBufferSize(50);
            shortLived.setTaskRetentionMs(1L);
            shortLived.init();
            ReflectionTestUtils.setField(registry, "sseProperties", shortLived);
            Thread.sleep(10);

            registry.purgeExpiredTasks();

            assertThat(registry.trackedTaskCount()).isZero();
            assertThat(registry.subscribe(APP_ID, 0L).toString()).contains("FluxJust");
        }

        @Test
        @DisplayName("purge 不得清理仍在运行的任务")
        void shouldNotPurgeRunningTask() throws Exception {
            registry.startTask(APP_ID);

            SseProperties shortLived = new SseProperties();
            shortLived.setTaskRetentionMs(1L);
            shortLived.init();
            ReflectionTestUtils.setField(registry, "sseProperties", shortLived);
            Thread.sleep(10);

            registry.purgeExpiredTasks();

            assertThat(registry.runningTaskIds()).containsExactly(APP_ID);
        }
    }

}
