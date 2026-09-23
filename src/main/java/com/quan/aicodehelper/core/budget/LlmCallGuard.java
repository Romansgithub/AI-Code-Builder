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
 * LLM 调用预算闸门。调用分散在生成、路由、自愈、评测四条路径，彼此不知对方花了多少，故计数必须集中在
 * 唯一交汇处累加，否则「新增路径忘了计数」只会静默漏账。只统计次数而非 token（详见 docs/design-notes.md 第二节）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmCallGuard {

    private final LlmBudgetProperties budgetProperties;

    private final AtomicLong usedCalls = new AtomicLong(0L);

    /** 被拦下的次数；与实际调用分开统计，便于从日志区分「用完了」和「被拦几次」。 */
    private final AtomicLong rejectedCalls = new AtomicLong(0L);

    /** 保证阈值预警只打一次，否则超阈值后每次调用都刷 WARN 会淹没日志。 */
    private volatile boolean thresholdWarned = false;

    @PostConstruct
    public void init() {
        log.info("LLM 调用预算闸门就绪：{}", describeUsage());
    }

    /**
     * 申请一次调用额度。必须在真正发起 LLM 请求之前调用，顺序反了等于「先花钱再记账」，闸门失效。
     *
     * @throws BusinessException 预算耗尽且开启拒绝策略时
     */
    public void acquire(String scene) {
        if (!budgetProperties.isEnabled()) {
            return;
        }

        long used = usedCalls.incrementAndGet();
        int max = budgetProperties.getMaxCalls();

        if (used > max) {
            // 超出上限，把这次不该记的账退回去，否则计数器虚高、「实际调用数」不准
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
     * 只检查额度、不消耗计数：供「先做不可逆准备再发调用」的场景预检。检查与随后的 acquire
     * 之间有竞态，最坏只是准备工作白做一次，代价远小于账目虚高。
     *
     * @throws BusinessException 预算已耗尽且开启拒绝策略时
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

    public long getUsedCalls() {
        return usedCalls.get();
    }

    public long getRejectedCalls() {
        return rejectedCalls.get();
    }

    public long getRemainingCalls() {
        return Math.max(0L, budgetProperties.getMaxCalls() - usedCalls.get());
    }

    public String describeUsage() {
        return String.format("已用 %d / %d 次，被拦截 %d 次，剩余 %d 次（enabled=%s）",
                getUsedCalls(), budgetProperties.getMaxCalls(),
                getRejectedCalls(), getRemainingCalls(), budgetProperties.isEnabled());
    }

    /** 供开发联调重置，不必重启服务即可续上额度。 */
    public void reset() {
        long previous = usedCalls.getAndSet(0L);
        rejectedCalls.set(0L);
        thresholdWarned = false;
        log.info("LLM 调用预算计数已重置（此前已用 {} 次）", previous);
    }
}
