package com.quan.aicodehelper.core.eval;

/**
 * 评测跑批的作用域标记。
 *
 * <p><b>为什么需要它</b>：评测跑批复用生产的生成链路，但两条链路对「构建」
 * 的诉求正好相反 ——</p>
 * <ul>
 *   <li>生产：生成完让 {@code buildVueProjectAsync} 在虚拟线程里异步构建，
 *       不能阻塞 SSE 流，用户通过进度事件看结果；</li>
 *   <li>评测：需要拿到 <b>同步的、结构化的</b> 构建结果（首轮是否通过、自愈几轮），
 *       所以自己调 {@code buildWithHealingOutcome}。</li>
 * </ul>
 *
 * <p>如果两边都跑，同一个产物目录上会并发出现两次 {@code npm install} /
 * {@code npm run build}：互相抢 {@code node_modules} 与 {@code dist}，
 * 耗时数据失真，还会往评测表里多写一条重复记录。
 * 因此评测期间必须把生产侧那次异步构建关掉。</p>
 *
 * <p><b>为什么用 ThreadLocal 而不是字段</b>：跑批的每条用例跑在独立虚拟线程上，
 * 标记必须是线程级的；用普通字段会让标记泄漏到跑批之外的真实用户请求。</p>
 *
 * <p>注意：虚拟线程同样支持 ThreadLocal，这里依赖的是「用例线程」这一层级，
 * 而不是平台线程复用，所以不会有孤儿值残留。</p>
 */
public final class EvalScope {

    private EvalScope() {
    }

    private static final ThreadLocal<Boolean> IN_EVAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 当前线程是否处于评测跑批中。
     */
    public static boolean isActive() {
        return Boolean.TRUE.equals(IN_EVAL.get());
    }

    /**
     * 在评测作用域内执行，结束后必然还原 —— 即使抛异常也不能把标记漏出去，
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
