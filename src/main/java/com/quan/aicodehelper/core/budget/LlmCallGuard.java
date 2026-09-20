package com.quan.aicodehelper.core.budget;

import com.quan.aicodehelper.config.LlmBudgetProperties;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.exception.ErrorCode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 调用预算闸门。
 *
 * <p><b>它解决的具体问题</b>：这个项目的 LLM 调用分散在四条路径上
 * （生成、路由、自愈、评测），彼此不知道对方花了多少。结果是
 * 「自愈重试两次」和「评测跑 8 条用例」这类操作的实际成本，
 * 在调用方看来和「生成一次」没有区别 —— 直到额度耗尽。</p>
 *
 * <p><b>为什么计数要放在这里、而不是各个调用点自己数</b>：
 * 计数一旦分散，就必然出现「新增一条调用路径但忘了计数」的漏洞，
 * 而这类漏洞不会报错，只会静默漏账。所以闸门必须是**唯一入口**，
 * 且放在所有路径的交汇处。</p>
 *
 * <p><b>为什么用 AtomicLong 而不是 synchronized 或 LongAdder</b>：
 * 调用发生在多个虚拟线程上（生成跑在 {@code gen-task-*} 虚拟线程，
 * 自愈还可能在别的线程），需要原子性；但每次调用只是一个 CAS 自增，
 * 竞争极低，{@code AtomicLong} 足够且没有锁开销。
 * {@code LongAdder} 更适合高频写、低频读的场景，这里读写频率相当，
 * 且需要「读到精确值」来做阈值判断，故不用。</p>
 *
 * <p><b>一个刻意的取舍</b>：只统计「调用次数」而非 token。原因见
 * {@link LlmBudgetProperties} 的类注释 —— 次数能在发起前拦截，
 * token 只能事后统计。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCallGuard {

    private final LlmBudgetProperties budgetProperties;

    /**
     * 已消耗的调用次数。
     */
    private final AtomicLong usedCalls = new AtomicLong(0L);

    /**
     * 因为预算耗尽被拦下的次数 —— 和「实际调用」分开统计，
     * 这样看日志能区分「用完了」和「被拦了几次」。
     */
    private final AtomicLong rejectedCalls = new AtomicLong(0L);

    /**
     * 是否已经打过阈值警告。
     *
     * <p>用一个标志位保证预警**只打一次**。否则超过阈值后的每一次调用
     * 都会刷一条 WARN，几千次调用就能把日志淹掉，反而看不清关键信息。</p>
     */
    private volatile boolean thresholdWarned = false;

    @PostConstruct
    public void init() {
        log.info("LLM 调用预算闸门就绪：{}", describeUsage());
    }

    /**
     * 申请一次调用额度，申请不到就抛异常。
     *
     * <p>调用方应在**真正发起 LLM 请求之前**调用本方法。顺序反了就等于
     * 「先花钱再记账」，闸门失去意义。</p>
     *
     * @param scene 调用场景，仅用于日志定位（如「代码生成」「类型路由」「构建自愈」）
     * @throws BusinessException 预算耗尽且开启了拒绝策略时
     */
    public void acquire(String scene) {
        if (!budgetProperties.isEnabled()) {
            return;
        }

        long used = usedCalls.incrementAndGet();
        int max = budgetProperties.getMaxCalls();

        if (used > max) {
            // 超出上限，把这次不该记的账退回去 —— 否则计数器会虚高，
            // 「实际调用数」就不准了
            usedCalls.decrementAndGet();
            long rejected = rejectedCalls.incrementAndGet();

            // 拒绝日志只在前几次和每 10 次时打印，避免刷屏
            if (rejected <= 3 || rejected % 10 == 0) {
                log.warn("LLM 预算已耗尽，拒绝调用：场景={}，已拦截 {} 次（上限 {}）",
                        scene, rejected, max);
            }

            if (budgetProperties.isRejectWhenExhausted()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        String.format("AI 调用次数已达上限（%d 次），请重启服务或调整 app.budget.max-calls 后重试", max));
            }
            return;
        }

        warnIfApproachingLimit(used, max, scene);
    }

    /**
     * 到达预警阈值时打一次警告。
     */
    private void warnIfApproachingLimit(long used, int max, String scene) {
        if (thresholdWarned) {
            return;
        }
        if (used >= max * budgetProperties.getWarnRatio()) {
            thresholdWarned = true;
            log.warn("LLM 调用量已达上限的 {}%（{} / {} 次），场景={}。"
                            + "继续调用将在 {} 次后被拒绝。",
                    (int) (budgetProperties.getWarnRatio() * 100), used, max, scene,
                    max - used + 1);
        }
    }

    /**
     * 只检查额度是否可用，**不消耗**计数。
     *
     * <p><b>为什么需要它</b>：有些调用方要先做不可逆的准备工作（例如抢占任务现场、
     * 写库、建目录），再真正发起 LLM 调用。这类调用方需要在动手之前先确认
     * 「这次做下去不会因为额度不足而半途失败」，但又不能提前计数 ——
     * 提前计数会让「检查」变成「占用」，随后真正的调用再记一次就是双份。</p>
     *
     * <p>它与 {@link #acquire} 是配套的：先 {@code assertAvailable} 探路，
     * 真正调用前再 {@code acquire} 落账。检查与落账之间理论上存在竞态
     * （检查通过、落账时已被别人抢光），但那个场景下 {@code acquire} 依然会
     * 正常抛异常，只是准备工作白做一次 —— 相比「账目虚高一倍」，
     * 这个代价明显更可接受。</p>
     *
     * @param scene 场景名，仅用于日志
     * @throws BusinessException 预算已耗尽且开启了拒绝策略时
     */
    public void assertAvailable(String scene) {
        if (!budgetProperties.isEnabled()) {
            return;
        }
        if (!budgetProperties.isRejectWhenExhausted()) {
            return;
        }
        if (usedCalls.get() >= budgetProperties.getMaxCalls()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    String.format("AI 调用次数已达上限（%d 次），请重启服务或调整 app.budget.max-calls 后重试",
                            budgetProperties.getMaxCalls()));
        }
    }

    /**
     * 当前已用次数。
     */
    public long getUsedCalls() {
        return usedCalls.get();
    }

    /**
     * 被拦截次数。
     */
    public long getRejectedCalls() {
        return rejectedCalls.get();
    }

    /**
     * 剩余可用次数（可为 0，不会为负）。
     */
    public long getRemainingCalls() {
        return Math.max(0L, budgetProperties.getMaxCalls() - usedCalls.get());
    }

    /**
     * 人可读的用量摘要，供启动日志与排查使用。
     */
    public String describeUsage() {
        return String.format("已用 %d / %d 次，被拦截 %d 次，剩余 %d 次（enabled=%s）",
                getUsedCalls(), budgetProperties.getMaxCalls(),
                getRejectedCalls(), getRemainingCalls(), budgetProperties.isEnabled());
    }

    /**
     * 手动重置计数。
     *
     * <p>做成公开方法是为了让开发联调时不必重启服务就能「续上额度」——
     * 重启一次要重新登录、重新开东西，代价比按需重置大。</p>
     */
    public void reset() {
        long previous = usedCalls.getAndSet(0L);
        rejectedCalls.set(0L);
        thresholdWarned = false;
        log.info("LLM 调用预算计数已重置（此前已用 {} 次）", previous);
    }
}
