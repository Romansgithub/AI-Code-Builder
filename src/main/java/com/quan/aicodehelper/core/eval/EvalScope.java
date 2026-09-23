package com.quan.aicodehelper.core.eval;

/**
 * 评测跑批的作用域标记：评测期间把生产侧的异步构建关掉。
 *
 * <p>生产与评测对「构建」的诉求相反：生产要异步构建、不阻塞 SSE 流；评测要同步拿到结构化结果。
 * 两边都跑会在同一产物目录上并发两次 npm install / npm run build，互抢 node_modules 与 dist，
 * 耗时失真且多写一条重复记录。标记必须是线程级的，否则会泄漏到跑批之外的真实用户请求。</p>
 */
public final class EvalScope {

    private EvalScope() {
    }

    private static final ThreadLocal<Boolean> IN_EVAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean isActive() {
        return Boolean.TRUE.equals(IN_EVAL.get());
    }

    /**
     * 在评测作用域内执行，结束后必然还原：即使抛异常也不能把标记漏出去，
     * 否则之后所有真实用户请求都会被当成评测请求、构建被静默跳过。
     */
    public static void runInScope(Runnable action) {
        boolean previous = isActive();
        IN_EVAL.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            if (previous) {
                IN_EVAL.set(Boolean.TRUE);
            } else {
                IN_EVAL.remove();
            }
        }
    }
}
