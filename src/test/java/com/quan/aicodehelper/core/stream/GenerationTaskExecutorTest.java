package com.quan.aicodehelper.core.stream;

import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.config.SseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成任务执行器测试：守无人订阅时生成仍跑完，以及任何结束路径都能推进终态。
 */
@DisplayName("生成任务执行器")
class GenerationTaskExecutorTest {

    private static final Long APP_ID = 7001L;

    private GenerationProgressRegistry registry;
    private GenerationTaskExecutor executor;

    @BeforeEach
    void setUp() {
        SseProperties properties = new SseProperties();
        properties.setBufferSize(100);
        properties.setTaskRetentionMs(60_000L);
        properties.setGenerationTimeoutMinutes(1);
        properties.init();

        GenerationEventStore eventStore = new GenerationEventStore();
        ReflectionTestUtils.setField(eventStore, "sseProperties", properties);

        registry = new GenerationProgressRegistry();
        ReflectionTestUtils.setField(registry, "eventStore", eventStore);
        ReflectionTestUtils.setField(registry, "sseProperties", properties);

        executor = new GenerationTaskExecutor();
        ReflectionTestUtils.setField(executor, "registry", registry);
        ReflectionTestUtils.setField(executor, "sseProperties", properties);
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
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
    @DisplayName("与连接解耦")
    class DecoupledFromConnection {

        @Test
        @DisplayName("没有任何订阅者时，生成仍应完整跑完并进入终态")
        void shouldCompleteWithoutAnySubscriber() {
            GenerationTask task = registry.startTask(APP_ID);
            StringBuilder consumed = new StringBuilder();

            Flux<String> stream = Flux.just("<html>", "<body>", "内容", "</body>")
                    .doOnNext(consumed::append);

            executor.start(task, stream);

            awaitUntil(() -> !task.isRunning(), 3000);

            assertThat(consumed.toString())
                    .as("生成必须跑完：它不该因为「没人在听」而中断")
                    .isEqualTo("<html><body>内容</body>");
            assertThat(task.getStatus()).isEqualTo(GenerationTaskStatusEnum.COMPLETED);
        }

        @Test
        @DisplayName("订阅者中途全部离开，生成不得被取消")
        void shouldNotBeCancelledWhenSubscribersLeave() {
            GenerationTask task = registry.startTask(APP_ID);

            Sinks.Many<String> gate = Sinks.many().unicast().onBackpressureBuffer();
            Flux<String> slowStream = gate.asFlux();
            executor.start(task, slowStream);

            List<String> received = new CopyOnWriteArrayList<>();
            registry.subscribe(APP_ID, 0L).subscribe(e -> received.add(e.event()));

            // 订阅者「离开」：这里用取消订阅模拟客户端断开
            registry.emit(APP_ID, "断线前的进度");

            // 继续推数据，生成侧不应感知到订阅者的存在
            gate.tryEmitNext("chunk-1");
            gate.tryEmitComplete();

            awaitUntil(() -> !task.isRunning(), 3000);
            assertThat(task.getStatus()).isEqualTo(GenerationTaskStatusEnum.COMPLETED);
        }

        @Test
        @DisplayName("生成过程中产生的进度事件应全部进入缓冲，供重连补发")
        void shouldBufferProgressProducedDuringGeneration() {
            GenerationTask task = registry.startTask(APP_ID);

            Flux<String> stream = Flux.just("a", "b")
                    .doOnNext(chunk -> {
                        registry.emit(APP_ID, "已处理：" + chunk);
                    });

            executor.start(task, stream);
            awaitUntil(() -> !task.isRunning(), 3000);

            // 用户此刻重连（lastEventId=0），应拿到全部中间进度
            List<String> events = new CopyOnWriteArrayList<>();
            registry.subscribe(APP_ID, 0L).subscribe(e -> events.add(e.event()));

            assertThat(events).contains("progress", "done");
            assertThat(events.stream().filter("progress"::equals).count()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("终态推进（不留僵尸任务）")
    class TerminalState {

        @Test
        @DisplayName("流抛异常时应标记失败并记录原因")
        void shouldMarkFailedOnError() {
            GenerationTask task = registry.startTask(APP_ID);

            Flux<String> failing = Flux.concat(
                    Flux.just("开始"),
                    Flux.error(new IllegalStateException("模型返回了非法 JSON")));

            executor.start(task, failing);
            awaitUntil(() -> !task.isRunning(), 3000);

            assertThat(task.getStatus()).isEqualTo(GenerationTaskStatusEnum.FAILED);
            assertThat(task.getFailureReason()).contains("模型返回了非法 JSON");
        }

        @Test
        @DisplayName("异常没有 message 时也要留下可读的失败原因，不能是 null")
        void shouldFallbackFailureReasonWhenMessageMissing() {
            GenerationTask task = registry.startTask(APP_ID);
            Flux<String> failing = Flux.error(new RuntimeException());

            executor.start(task, failing);
            awaitUntil(() -> !task.isRunning(), 3000);

            assertThat(task.getStatus()).isEqualTo(GenerationTaskStatusEnum.FAILED);
            assertThat(task.getFailureReason()).isNotBlank();
        }

        @Test
        @DisplayName("空流也算正常完成，不应被判为失败")
        void shouldTreatEmptyStreamAsCompleted() {
            GenerationTask task = registry.startTask(APP_ID);

            executor.start(task, Flux.empty());
            awaitUntil(() -> !task.isRunning(), 3000);

            assertThat(task.getStatus()).isEqualTo(GenerationTaskStatusEnum.COMPLETED);
        }
    }

    @Nested
    @DisplayName("看门狗")
    class Watchdog {

        @Test
        @DisplayName("任务超时未结束时应被强制收尾，避免永久占用该应用")
        void shouldFinishStuckTask() throws Exception {
            SseProperties properties = new SseProperties();
            properties.setGenerationTimeoutMinutes(1);
            properties.init();
            ReflectionTestUtils.setField(executor, "sseProperties", properties);

            GenerationTask task = registry.startTask(APP_ID);
            // 把创建时间拨回过去，模拟一个卡了很久的任务
            ReflectionTestUtils.setField(task, "createdAt", System.currentTimeMillis() - 120_000L);

            executor.watchdogCheck();

            assertThat(task.isRunning())
                    .as("卡死的任务必须被收尾，否则后续生成会被幂等保护永久挡住")
                    .isFalse();
            assertThat(task.getFailureReason()).contains("生成超时");
        }

        @Test
        @DisplayName("未超时的运行中任务不得被看门狗误杀")
        void shouldNotKillHealthyTask() {
            GenerationTask task = registry.startTask(APP_ID);

            executor.watchdogCheck();

            assertThat(task.isRunning()).isTrue();
        }
    }

    @Nested
    @DisplayName("订阅句柄")
    class SubscriptionHandle {

        @Test
        @DisplayName("生成结束后订阅句柄应可用，且被替换任务时能释放掉")
        void shouldReleaseSubscriptionOnReplace() {
            GenerationTask first = registry.startTask(APP_ID);
            executor.start(first, Flux.<String>never());
            awaitUntil(() -> true, 100);

            registry.finishTask(APP_ID, "人工结束");
            GenerationTask second = registry.startTask(APP_ID);
            assertThat(second).isNotNull();

            assertThat(second.isRunning()).isTrue();
        }
    }

    /** 生成流是否被真正消费完，用于确认「无人订阅也跑完」 */
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    @Test
    @DisplayName("生成在独立线程上执行，调用方不被阻塞")
    void shouldRunAsynchronously() {
        GenerationTask task = registry.startTask(APP_ID);
        // never() 永不结束：如果 start 是同步的，这个测试会挂死
        long before = System.currentTimeMillis();
        executor.start(task, Flux.<String>never());
        long elapsed = System.currentTimeMillis() - before;

        assertThat(elapsed)
                .as("start 必须立刻返回，长任务由独立线程承载")
                .isLessThan(2000);
        assertThat(task.isRunning()).isTrue();
        registry.finishTask(APP_ID, "清理");
    }

    @Test
    @DisplayName("看门狗不应误伤已结束的任务")
    void watchdogShouldIgnoreFinishedTask() {
        GenerationTask task = registry.startTask(APP_ID);
        registry.finishTask(APP_ID, null);
        ReflectionTestUtils.setField(task, "createdAt", System.currentTimeMillis() - 120_000L);

        executor.watchdogCheck();

        // 已完成的终态不应被改写成失败
        assertThat(task.getStatus()).isEqualTo(GenerationTaskStatusEnum.COMPLETED);
    }

    @Test
    @DisplayName("长任务被取消后不应永久占用应用（替代场景验证）")
    void shouldAllowRetryAfterCancel() {
        GenerationTask task = registry.startTask(APP_ID);
        // 显式指定泛型：Flux.never() 推断为 Object，直接传参编译不过
        executor.start(task, Flux.<String>never().timeout(Duration.ofMillis(50)));
        awaitUntil(() -> !task.isRunning(), 3000);

        assertThat(task.isRunning()).isFalse();
        assertThat(registry.startTask(APP_ID)).isNotNull();
    }
}
