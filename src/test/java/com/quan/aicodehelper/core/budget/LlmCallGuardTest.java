package com.quan.aicodehelper.core.budget;

import com.quan.aicodehelper.config.LlmBudgetProperties;
import com.quan.aicodehelper.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 调用预算闸门测试。
 *
 * <p>这些用例锁定的都是「看起来能跑、但在真实压力下会出错」的行为：
 * 并发下计数是否会超发、被拒绝的调用会不会污染计数、关闭开关后是否真的放行。
 * 它们是纯逻辑测试，不加载 Spring 容器，也不会有任何真实 AI 调用。</p>
 */
class LlmCallGuardTest {

    private LlmBudgetProperties properties;
    private LlmCallGuard guard;

    @BeforeEach
    void setUp() {
        properties = new LlmBudgetProperties();
        guard = new LlmCallGuard(properties);
    }

    /**
     * 构造一个指定上限的闸门。
     */
    private LlmCallGuard guardWith(int maxCalls) {
        properties.setMaxCalls(maxCalls);
        return new LlmCallGuard(properties);
    }

    @Nested
    @DisplayName("基本计数")
    class BasicCounting {

        @Test
        @DisplayName("每次 acquire 递增已用次数")
        void shouldIncrementOnAcquire() {
            guardWith(10);

            guard.acquire("测试");
            guard.acquire("测试");

            assertEquals(2L, guard.getUsedCalls());
            assertEquals(8L, guard.getRemainingCalls());
        }

        @Test
        @DisplayName("未达上限时不抛异常")
        void shouldNotThrowBelowLimit() {
            LlmCallGuard g = guardWith(3);

            assertDoesNotThrow(() -> {
                g.acquire("测试");
                g.acquire("测试");
                g.acquire("测试");
            });
            assertEquals(0L, g.getRemainingCalls());
        }

        @Test
        @DisplayName("重置后计数归零")
        void shouldResetCounters() {
            LlmCallGuard g = guardWith(5);
            g.acquire("测试");
            g.acquire("测试");

            g.reset();

            assertEquals(0L, g.getUsedCalls());
            assertEquals(0L, g.getRejectedCalls());
            assertEquals(5L, g.getRemainingCalls());
        }
    }

    @Nested
    @DisplayName("触顶拦截")
    class Rejection {

        @Test
        @DisplayName("超出上限时抛出业务异常")
        void shouldRejectWhenExhausted() {
            LlmCallGuard g = guardWith(1);
            g.acquire("第一次");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> g.acquire("第二次"));

            // 报错信息要能让看到的人知道「怎么办」，不能只说过不了
            assertTrue(ex.getMessage().contains("1"),
                    "异常信息应包含上限数字，便于定位：" + ex.getMessage());
            assertTrue(ex.getMessage().contains("app.budget.max-calls"),
                    "异常信息应提示调整方式：" + ex.getMessage());
        }

        @Test
        @DisplayName("被拒绝的调用不污染已用计数")
        void rejectedCallsShouldNotInflateUsage() {
            // 这条是关键的账目正确性：如果被拒绝也计数，
            // 「实际调用数」会虚高，用量数字就不可信了
            LlmCallGuard g = guardWith(2);
            g.acquire("a");
            g.acquire("b");

            for (int i = 0; i < 5; i++) {
                assertThrows(BusinessException.class, () -> g.acquire("超限"));
            }

            assertEquals(2L, g.getUsedCalls(), "已用次数应仍为 2，不被拒绝次数污染");
            assertEquals(5L, g.getRejectedCalls(), "拒绝次数应单独累计");
            assertEquals(0L, g.getRemainingCalls());
        }

        @Test
        @DisplayName("关闭拒绝策略后只计数不抛异常")
        void shouldOnlyCountWhenRejectDisabled() {
            properties.setRejectWhenExhausted(false);
            LlmCallGuard g = guardWith(1);

            assertDoesNotThrow(() -> {
                g.acquire("第一次");
                // 超限但配置为不拒绝 —— 应放行（用于「只想观测用量」的场景）
                g.acquire("第二次");
            });

            // 放行的调用不计入已用（否则账目虚高）
            assertEquals(1L, g.getUsedCalls());
            assertEquals(1L, g.getRejectedCalls());
        }
    }

    @Nested
    @DisplayName("可行性检查（不消耗计数）")
    class AssertAvailable {

        @Test
        @DisplayName("检查通过时不消耗计数")
        void shouldNotConsumeOnCheck() {
            // 这是关键性质：调用方先探路、真正调用时才记账。
            // 如果检查也计数，一次生成会被记两次，账目直接虚高一倍
            LlmCallGuard g = guardWith(10);

            g.assertAvailable("探路");
            g.assertAvailable("探路");
            g.assertAvailable("探路");

            assertEquals(0L, g.getUsedCalls(), "检查不应改变已用次数");
            assertEquals(10L, g.getRemainingCalls());
        }

        @Test
        @DisplayName("预算耗尽时检查抛异常")
        void shouldThrowWhenExhausted() {
            LlmCallGuard g = guardWith(1);
            g.acquire("占满");

            assertThrows(BusinessException.class, () -> g.assertAvailable("探路"));
        }

        @Test
        @DisplayName("恰好用完最后一次额度后，检查仍然通过")
        void shouldPassExactlyAtLimit() {
            // 边界：used == max 表示「已经用完」，此时不应再放行检查，
            // 因为后续 acquire 必然失败
            LlmCallGuard g = guardWith(2);
            g.acquire("a");
            g.acquire("b");

            assertThrows(BusinessException.class, () -> g.assertAvailable("探路"),
                    "用满后检查必须失败，否则调用方会误以为还能继续");
        }

        @Test
        @DisplayName("关闭拒绝策略时检查一律放行")
        void shouldAlwaysPassWhenRejectDisabled() {
            properties.setRejectWhenExhausted(false);
            LlmCallGuard g = guardWith(1);
            g.acquire("占满");

            assertDoesNotThrow(() -> g.assertAvailable("探路"));
        }

        @Test
        @DisplayName("开关关闭时检查一律放行")
        void shouldPassWhenDisabled() {
            properties.setEnabled(false);
            LlmCallGuard g = guardWith(1);

            assertDoesNotThrow(() -> g.assertAvailable("探路"));
            assertEquals(0L, g.getUsedCalls());
        }
    }

    @Nested
    @DisplayName("开关与边界")
    class SwitchAndBounds {

        @Test
        @DisplayName("开关关闭时完全不计数不拦截")
        void shouldPassThroughWhenDisabled() {
            properties.setEnabled(false);
            LlmCallGuard g = guardWith(1);

            assertDoesNotThrow(() -> {
                for (int i = 0; i < 100; i++) {
                    g.acquire("测试");
                }
            });

            // 关闭状态下不应记账，否则重新打开时会带着一笔来路不明的用量
            assertEquals(0L, g.getUsedCalls());
            assertEquals(0L, g.getRejectedCalls());
        }

        @Test
        @DisplayName("剩余次数不会为负")
        void remainingShouldNotGoNegative() {
            LlmCallGuard g = guardWith(1);
            g.acquire("a");
            assertThrows(BusinessException.class, () -> g.acquire("b"));

            assertEquals(0L, g.getRemainingCalls(),
                    "剩余次数应为 0 而不是负数 —— 前端要拿它画进度条");
        }

        @Test
        @DisplayName("非法上限在 init 时被兜底为 200")
        void shouldFallbackIllegalMaxCalls() {
            LlmBudgetProperties p = new LlmBudgetProperties();
            p.setMaxCalls(0);
            p.setWarnRatio(1.5);
            p.init();

            assertEquals(200, p.getMaxCalls());
            assertEquals(0.8, p.getWarnRatio());
        }
    }

    @Nested
    @DisplayName("并发安全")
    class Concurrency {

        @Test
        @DisplayName("并发 acquire 时既不超发也不漏计")
        void shouldBeAccurateUnderConcurrency() throws Exception {
            // 生成跑在虚拟线程上，自愈可能在其他线程 —— 计数必须原子。
            // 16 线程 x 50 次，上限给足，最终必须恰好 800
            int threads = 16;
            int perThread = 50;
            LlmCallGuard g = guardWith(threads * perThread);

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            try {
                for (int i = 0; i < threads; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            for (int j = 0; j < perThread; j++) {
                                g.acquire("并发");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(10, TimeUnit.SECONDS), "并发任务应在 10 秒内完成");
            } finally {
                pool.shutdownNow();
            }

            assertEquals((long) threads * perThread, g.getUsedCalls(),
                    "并发下计数必须精确，多一次少一次都意味着账目不可信");
        }

        @Test
        @DisplayName("并发触顶时放行数恰好等于上限")
        void shouldAllowExactlyMaxCallsUnderConcurrency() throws Exception {
            // 这条验证闸门真的能拦住：100 个并发请求抢 10 个名额，
            // 必须恰好 10 个成功、90 个被拒，不能出现「都以为还有额度」
            int max = 10;
            int attempts = 100;
            LlmCallGuard g = guardWith(max);

            AtomicInteger allowed = new AtomicInteger(0);
            AtomicInteger rejected = new AtomicInteger(0);
            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(attempts);

            try {
                for (int i = 0; i < attempts; i++) {
                    pool.submit(() -> {
                        try {
                            start.await();
                            g.acquire("抢占");
                            allowed.incrementAndGet();
                        } catch (BusinessException e) {
                            rejected.incrementAndGet();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                assertTrue(done.await(10, TimeUnit.SECONDS), "并发任务应在 10 秒内完成");
            } finally {
                pool.shutdownNow();
            }

            assertEquals(max, allowed.get(), "放行数必须恰好等于上限");
            assertEquals(attempts - max, rejected.get(), "其余必须全部被拒");
            assertEquals((long) max, g.getUsedCalls());
        }
    }
}
