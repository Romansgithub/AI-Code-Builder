package com.quan.aicodehelper.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 大模型调用预算配置。
 *
 * <p><b>为什么需要它</b>：这个项目里有四条会真实消耗额度的路径 —— 代码生成、
 * 类型路由、构建自愈、评测跑批。它们的共同问题是「单次调用不贵，但都不受约束」：
 * 自愈一轮最多翻 3 倍，评测一轮是 8 条用例 × 每条最多 3 次生成。开发联调时
 * 随手多点几次，免费额度就见底了，而且**过程中没有任何提示**。</p>
 *
 * <p><b>为什么用「调用次数」而不是「token 数」做预算</b>：token 数只有在响应
 * 结束后（甚至流式响应完全读完）才知道，等发现超了额度早就花出去了，
 * 属于「事后审计」。调用次数在**发起前**就能判定，可以真正拦住下一次调用。
 * 两者的关系是：次数是闸门（事前，能拦），token 是仪表盘（事后，能看）。
 * 这里优先做闸门。</p>
 *
 * <p><b>为什么默认是开启的</b>：一个「默认关闭」的预算等于没有预算 ——
 * 它会重蹈 {@code app.eval.enabled} 默认写成 true 的覆辙。
 * 默认值取一个明显够日常开发、但又能挡住失控的数字。</p>
 *
 * <p><b>计数是进程内的</b>：重启即清零。这对开发场景是合理的
 * （重启本来就意味着「重新开始一轮」），但也意味着它不是配额系统 ——
 * 真要跨实例、跨重启地限流，需要把计数器换成 Redis。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.budget")
public class LlmBudgetProperties {

    /**
     * 总开关。关闭后所有调用直接放行（等于恢复改造前的行为）。
     */
    private boolean enabled = true;

    /**
     * 进程生命周期内允许的 LLM 调用总次数上限。
     *
     * <p>默认 200：一次完整开发联调通常远用不到，但如果跑评测或反复试自愈
     * 就会撞线 —— 正是我们希望被拦住的场景。</p>
     */
    private int maxCalls = 200;

    /**
     * 用量达到该比例时打警告日志。
     *
     * <p>0.8 的含义：还剩 20% 时就开始提醒，而不是等撞线才知道。</p>
     */
    private double warnRatio = 0.8;

    /**
     * 触顶时是否直接拒绝调用。
     *
     * <p>设为 false 则只记录不拦截，用于「只想观测用量」的场景。</p>
     */
    private boolean rejectWhenExhausted = true;

    @PostConstruct
    public void init() {
        if (maxCalls <= 0) {
            log.warn("预算上限非法（{}），必须是正数，已回退为 200", maxCalls);
            maxCalls = 200;
        }
        if (warnRatio <= 0 || warnRatio >= 1) {
            log.warn("预警比例非法（{}），应在 (0,1) 区间，已回退为 0.8", warnRatio);
            warnRatio = 0.8;
        }
        if (!enabled) {
            log.warn("LLM 调用预算已关闭（app.budget.enabled=false），调用将不受次数限制");
        } else {
            log.info("LLM 调用预算：上限 {} 次，预警阈值 {}%，触顶后{}",
                    maxCalls, (int) (warnRatio * 100), rejectWhenExhausted ? "拒绝" : "仅告警");
        }
    }
}
