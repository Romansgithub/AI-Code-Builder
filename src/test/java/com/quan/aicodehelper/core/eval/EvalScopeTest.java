package com.quan.aicodehelper.core.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评测作用域标记测试：该标记控制生产侧异步构建是否让路。
 * 一旦泄漏，真实用户的 Vue 项目会静默永远不构建，属最难发现的故障，边界需逐条钉死。
 */
@DisplayName("评测作用域标记")
class EvalScopeTest {

    @Nested
    @DisplayName("基本语义")
    class Basic {

        @Test
        @DisplayName("默认不在评测作用域内")
        void shouldBeInactiveByDefault() throws Exception {
            AtomicBoolean active = new AtomicBoolean(true);

            Thread worker = Thread.ofVirtual().start(() -> active.set(EvalScope.isActive()));
            worker.join();

            assertThat(active.get()).isFalse();
        }

        @Test
        @DisplayName("作用域内应识别为评测中")
        void shouldBeActiveInsideScope() {
            AtomicBoolean inside = new AtomicBoolean(false);

            EvalScope.runInScope(() -> inside.set(EvalScope.isActive()));

            assertThat(inside.get()).isTrue();
        }
    }

    @Nested
    @DisplayName("隔离性")
    class Isolation {

        @Test
        @DisplayName("标记只作用于当前线程，不泄漏到其他线程")
        void shouldNotLeakToOtherThreads() throws Exception {
            AtomicBoolean otherThreadSees = new AtomicBoolean(true);

            EvalScope.runInScope(() -> {
                Thread worker = Thread.ofVirtual().start(() ->
                        otherThreadSees.set(EvalScope.isActive()));
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(otherThreadSees.get())
                    .as("真实用户请求跑在别的线程上，不该被评测标记波及")
                    .isFalse();
        }

        @Test
        @DisplayName("作用域结束后应还原为不在评测中")
        void shouldRestoreAfterScope() {
            EvalScope.runInScope(() -> {
            });

            assertThat(EvalScope.isActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("异常安全")
    class ExceptionSafety {

        @Test
        @DisplayName("抛出异常也必须还原标记")
        void shouldRestoreWhenThrowing() {
            assertThatThrownBy(() -> EvalScope.runInScope(() -> {
                throw new IllegalStateException("构建挂了");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(EvalScope.isActive())
                    .as("不还原会让后续真实用户的 Vue 项目静默不构建")
                    .isFalse();
        }

        @Test
        @DisplayName("嵌套时内层退出不应把外层标记一起清掉")
        void shouldKeepOuterScopeActiveOnNestedExit() {
            AtomicReference<Boolean> outerAfterInner = new AtomicReference<>();

            EvalScope.runInScope(() -> {
                EvalScope.runInScope(() -> {
                    // 内层
                });
                outerAfterInner.set(EvalScope.isActive());
            });

            assertThat(outerAfterInner.get())
                    .as("内层退出后外层仍在评测中")
                    .isTrue();
            assertThat(EvalScope.isActive()).isFalse();
        }
    }
}
