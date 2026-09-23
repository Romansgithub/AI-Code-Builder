package com.quan.aicodehelper.core.stream;

import com.quan.aicodehelper.config.SseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成进度事件缓冲测试：守的是断线重连的地基，断言集中在顺序、缺口、淘汰。
 */
@DisplayName("生成进度事件缓冲")
class GenerationEventStoreTest {

    private static final Long APP_ID = 3001L;

    private SseProperties properties;
    private GenerationEventStore store;

    @BeforeEach
    void setUp() {
        properties = new SseProperties();
        properties.setBufferSize(5);
        properties.setTaskRetentionMs(60_000L);
        properties.init();

        store = new GenerationEventStore();
        ReflectionTestUtils.setField(store, "sseProperties", properties);
    }

    @Nested
    @DisplayName("事件追加与读取")
    class AppendAndRead {

        @Test
        @DisplayName("id 应从 1 开始单调递增，不允许重复")
        void shouldAssignMonotonicIds() {
            store.append(APP_ID, "progress", "第一条");
            store.append(APP_ID, "progress", "第二条");
            store.append(APP_ID, "progress", "第三条");

            List<GenerationEvent> events = store.snapshot(APP_ID);
            assertThat(events).extracting(GenerationEvent::id)
                    .containsExactly(1L, 2L, 3L);
        }

        @Test
        @DisplayName("since 只返回严格大于 lastEventId 的事件（补发的核心语义）")
        void shouldReturnOnlyUnreadEvents() {
            for (int i = 1; i <= 4; i++) {
                store.append(APP_ID, "progress", "事件" + i);
            }

            List<GenerationEvent> unread = store.since(APP_ID, 2L);
            assertThat(unread).extracting(GenerationEvent::id).containsExactly(3L, 4L);
            assertThat(unread).extracting(GenerationEvent::data).containsExactly("事件3", "事件4");
        }

        @Test
        @DisplayName("lastEventId 为 0 表示全量读取（首次连接）")
        void shouldReturnAllWhenFromZero() {
            store.append(APP_ID, "progress", "唯一一条");
            assertThat(store.since(APP_ID, 0L)).hasSize(1);
        }

        @Test
        @DisplayName("已追上进度时返回空列表，而不是报错")
        void shouldReturnEmptyWhenCaughtUp() {
            store.append(APP_ID, "progress", "一条");
            assertThat(store.since(APP_ID, 1L)).isEmpty();
            // 传入比水位还大的 id（前端状态异常）也不应炸
            assertThat(store.since(APP_ID, 999L)).isEmpty();
        }

        @Test
        @DisplayName("不同应用的事件互不串流")
        void shouldIsolateBetweenApps() {
            store.append(APP_ID, "progress", "A 应用");
            store.append(4002L, "progress", "B 应用");

            assertThat(store.since(APP_ID, 0L)).hasSize(1);
            assertThat(store.since(APP_ID, 0L).get(0).data()).isEqualTo("A 应用");
            assertThat(store.since(4002L, 0L)).hasSize(1);
            // id 各自从 1 起算，互不影响
            assertThat(store.since(4002L, 0L).get(0).id()).isEqualTo(1L);
        }

        @Test
        @DisplayName("未登记过的应用读取时返回空列表而不是抛异常")
        void shouldHandleUnknownApp() {
            assertThat(store.since(9999L, 0L)).isEmpty();
            assertThat(store.currentMaxId(9999L)).isZero();
        }

        @Test
        @DisplayName("appId 为 null 时静默跳过，不得抛异常")
        void shouldTolerateNullAppId() {
            assertThat(store.append(null, "progress", "x")).isNull();
            assertThat(store.since(null, 0L)).isEmpty();
        }
    }

    @Nested
    @DisplayName("有界缓冲与淘汰")
    class BoundedBuffer {

        @Test
        @DisplayName("超出上限时应淘汰最旧的事件，总量不超过配置值")
        void shouldEvictOldestWhenExceedingLimit() {
            for (int i = 1; i <= 8; i++) {
                store.append(APP_ID, "progress", "事件" + i);
            }

            List<GenerationEvent> events = store.snapshot(APP_ID);
            assertThat(events).hasSize(5);
            assertThat(events).extracting(GenerationEvent::id).containsExactly(4L, 5L, 6L, 7L, 8L);
        }

        @Test
        @DisplayName("被淘汰的事件必须留下缺口记录，供前端解释进度缺失")
        void shouldTrackLostBoundary() {
            for (int i = 1; i <= 8; i++) {
                store.append(APP_ID, "progress", "事件" + i);
            }
            // 1~3 被淘汰，因此「已丢失的最小 id」应为 4
            assertThat(store.lostBefore(APP_ID)).isEqualTo(4L);
        }

        @Test
        @DisplayName("前端报告的 id 早于缺口时，补发结果里不应出现比它更旧的数据")
        void shouldNotReturnEvictedEvents() {
            for (int i = 1; i <= 8; i++) {
                store.append(APP_ID, "progress", "事件" + i);
            }
            List<GenerationEvent> unread = store.since(APP_ID, 0L);
            assertThat(unread).extracting(GenerationEvent::id).containsExactly(4L, 5L, 6L, 7L, 8L);
            assertThat(unread).noneMatch(e -> e.id() < 4L);
        }

        @Test
        @DisplayName("按时间清理只淘汰旧事件，保留新事件")
        void shouldPurgeByTime() throws Exception {
            store.append(APP_ID, "progress", "旧事件");
            GenerationEvent old = store.snapshot(APP_ID).get(0);
            // 以旧事件自身时刻 +1ms 为界，避免分界点压在新事件上把它一起清掉（不依赖 sleep 精度）
            long cutoff = old.created() + 1;

            // 这里必须真的让时间往前走一点，新事件才会落在 cutoff 之后
            Thread.sleep(5);
            store.append(APP_ID, "progress", "新事件");

            store.purgeExpired(cutoff);

            List<GenerationEvent> remaining = store.snapshot(APP_ID);
            assertThat(remaining).extracting(GenerationEvent::data).containsExactly("新事件");
            assertThat(store.lostBefore(APP_ID)).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("水位与回收")
    class WatermarkAndEviction {

        @Test
        @DisplayName("水位应等于已分配的最大 id，用于实时/补发去重")
        void shouldReportWatermark() {
            assertThat(store.currentMaxId(APP_ID)).isZero();
            store.append(APP_ID, "progress", "一");
            store.append(APP_ID, "progress", "二");
            assertThat(store.currentMaxId(APP_ID)).isEqualTo(2L);
        }

        @Test
        @DisplayName("回收后该应用的事件全部消失，且可重新从 1 开始计数")
        void shouldResetAfterEviction() {
            store.append(APP_ID, "progress", "一");
            store.append(APP_ID, "progress", "二");
            assertThat(store.trackedCount()).isEqualTo(1);

            store.evict(APP_ID);

            assertThat(store.trackedCount()).isZero();
            assertThat(store.since(APP_ID, 0L)).isEmpty();
            // 回收即彻底重置：新任务的事件应重新从 1 开始
            store.append(APP_ID, "progress", "重新开始");
            assertThat(store.since(APP_ID, 0L).get(0).id()).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("并发安全")
    class Concurrency {

        @Test
        @DisplayName("多线程并发追加时 id 不得重复，也不得丢失")
        void shouldNotLoseOrDuplicateIdsUnderConcurrency() throws Exception {
            int threads = 8;
            int perThread = 50;
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                workers[t] = new Thread(() -> {
                    for (int i = 0; i < perThread; i++) {
                        store.append(APP_ID, "progress", "并发");
                    }
                });
                workers[t].start();
            }
            for (Thread w : workers) {
                w.join();
            }

            // 缓冲只有 5 条，但「已分配的最大 id」必须等于总追加次数
            assertThat(store.currentMaxId(APP_ID)).isEqualTo((long) threads * perThread);
            List<GenerationEvent> kept = store.snapshot(APP_ID);
            assertThat(kept).hasSize(5);
            for (int i = 1; i < kept.size(); i++) {
                assertThat(kept.get(i).id()).isEqualTo(kept.get(i - 1).id() + 1);
            }
        }
    }
}
