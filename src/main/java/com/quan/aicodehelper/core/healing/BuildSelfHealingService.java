package com.quan.aicodehelper.core.healing;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.ai.factory.AiCodeGeneratorServiceFactory;
import com.quan.aicodehelper.ai.fallback.FallbackFailureClassifier;
import com.quan.aicodehelper.ai.service.AiCodeHealingService;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.config.SelfHealingProperties;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.core.builder.BuildErrorExtractor;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.builder.VueProjectBuilder;
import com.quan.aicodehelper.exception.BusinessException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 构建自愈服务：把构建报错追加回同一 AI 会话让它改代码，循环直至成功或用尽重试。
 * 只重试代码类问题；环境类与确定性失败立即中止，不空转。
 */
@Slf4j
@Service
public class BuildSelfHealingService {

    private static final String HEALING_PROMPT_RESOURCE = "prompt/build-self-healing-prompt.txt";

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private SelfHealingProperties selfHealingProperties;

    @Resource
    private GenerationProgressRegistry generationProgressRegistry;

    /** 预算闸门：自愈直连 service 不走 Facade，借不到那里的计数，必须在此过闸。 */
    @Resource
    private LlmCallGuard llmCallGuard;

    /**
     * @return 自愈成功返回成功结果；用尽重试仍失败则返回最后一次失败结果
     */
    public BuildResult buildWithHealing(String projectPath, Long appId) {
        return buildWithHealingOutcome(projectPath, appId).getBuildResult();
    }

    /**
     * 带自愈的构建，额外返回「首次是否通过」与「自愈轮数」供评测统计。
     */
    public HealingOutcome buildWithHealingOutcome(String projectPath, Long appId) {
        long startedAt = System.currentTimeMillis();
        BuildResult result = vueProjectBuilder.buildProjectWithResult(projectPath, appId);
        boolean firstBuildPassed = result.isSuccess();

        if (result.isSuccess()) {
            emit(appId, "项目构建成功");
            return HealingOutcome.of(result, true, 0, System.currentTimeMillis() - startedAt);
        }

        // 环境类问题不重试：改了代码也解决不了
        if (!result.isHealable()) {
            log.warn("构建失败且不可自愈（类型={}），直接返回：{}",
                    result.getFailureType(), result.getErrorMessage());
            emit(appId, "构建失败：" + result.getFailureType().getDescription());
            return HealingOutcome.of(result, false, 0, System.currentTimeMillis() - startedAt);
        }

        if (!selfHealingProperties.isActive()) {
            log.info("自愈未启用，跳过修复流程");
            emit(appId, "构建失败：" + result.getFailureType().getDescription());
            return HealingOutcome.of(result, false, 0, System.currentTimeMillis() - startedAt);
        }

        int maxAttempts = selfHealingProperties.getMaxAttempts();
        List<String> attemptErrors = new ArrayList<>();
        int executedAttempts = 0;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            executedAttempts = attempt;
            log.info("开始第 {}/{} 轮构建自愈，应用 {}，错误：{}",
                    attempt, maxAttempts, appId, StrUtil.maxLength(result.getErrorMessage(), 200));
            emit(appId, String.format("检测到构建错误，正在自动修复（第 %d/%d 次）...", attempt, maxAttempts));

            attemptErrors.add(describeAttempt(attempt, result));

            AiFixOutcome outcome = askAiToFix(appId, result, attempt, maxAttempts);

            if (outcome.status() == AiFixStatus.BUDGET_EXHAUSTED) {
                // 预算耗尽不是「没修好」而是「没资格再试」：continue 会让剩余轮次空转烧完，故直接跳出。
                log.warn("LLM 预算耗尽，中止构建自愈（已完成 {} 轮）", executedAttempts);
                emit(appId, "AI 调用额度已用尽，已停止自动修复，请手动检查构建错误");
                result.setErrorMessage(buildFinalErrorMessage(maxAttempts, attemptErrors, result));
                return HealingOutcome.of(result, false, executedAttempts,
                        System.currentTimeMillis() - startedAt);
            }

            if (outcome.status() == AiFixStatus.ABORTED) {
                // 中止 ≠ 本轮没修好：确定性失败再试一轮只会得到同样结果，必须立即终止。
                log.error("第 {}/{} 轮自愈被中止（重试无意义），终止自愈循环：{}",
                        attempt, maxAttempts, outcome.reason());
                emit(appId, "自动修复已中止：" + outcome.reason());
                result.setErrorMessage(buildAbortedErrorMessage(outcome.reason(), attemptErrors, result));
                return HealingOutcome.of(result, false, executedAttempts,
                        System.currentTimeMillis() - startedAt);
            }

            if (outcome.status() == AiFixStatus.NOT_REPAIRED) {
                log.warn("第 {} 轮自愈中，AI 未能给出有效修复（{}）", attempt, outcome.reason());
                emit(appId, "自动修复未生效，继续重试...");
                continue;
            }
            result = vueProjectBuilder.buildProjectWithResult(projectPath, appId);
            if (result.isSuccess()) {
                log.info("应用 {} 在第 {} 轮自愈后构建成功", appId, attempt);
                emit(appId, String.format("自动修复成功，项目已通过构建（共修复 %d 次）", attempt));
                return HealingOutcome.of(result, false, attempt, System.currentTimeMillis() - startedAt);
            }

            if (!result.isHealable()) {
                log.warn("第 {} 轮修复后失败类型变为 {}（不可自愈），终止重试",
                        attempt, result.getFailureType());
                emit(appId, "构建失败：" + result.getFailureType().getDescription());
                return HealingOutcome.of(result, false, attempt, System.currentTimeMillis() - startedAt);
            }

            log.warn("第 {} 轮修复后仍构建失败：{}", attempt,
                    StrUtil.maxLength(result.getErrorMessage(), 200));
        }

        log.warn("应用 {} 的构建自愈已用尽 {} 轮重试，仍然失败", appId, maxAttempts);
        emit(appId, String.format("已尝试自动修复 %d 次仍未通过构建，请手动检查代码", maxAttempts));
        result.setErrorMessage(buildFinalErrorMessage(maxAttempts, attemptErrors, result));
        return HealingOutcome.of(result, false, executedAttempts, System.currentTimeMillis() - startedAt);
    }

    /**
     * 让 AI 修复构建错误：复用同一 appId 的对话记忆，但不挂用户输入护栏
     * （护栏的 1000 字上限会把构建报错直接拒掉）。详见 {@link AiCodeHealingService}。
     *
     * @return 四态；压成 boolean 会让三种失败在外层退化成无脑重试
     */
    private AiFixOutcome askAiToFix(Long appId, BuildResult result, int attempt, int maxAttempts) {
        try {
            AiCodeHealingService healingService =
                    aiCodeGeneratorServiceFactory.getAiCodeHealingService(appId);

            String report = buildFailureReport(result);

            // 自愈直连 service，不经过 Facade，所以在真正发起调用前单独过闸
            llmCallGuard.acquire("构建自愈-第" + attempt + "轮");

            // 同步消费整个流：自愈在独立虚拟线程执行，此处阻塞不影响 SSE 主流程。
            StringBuilder reply = new StringBuilder();
            healingService.healVueProjectBuildFailure(appId, report)
                    .doOnNext(reply::append)
                    .blockLast();

            log.info("第 {}/{} 轮自愈，AI 回复：{}", attempt, maxAttempts,
                    StrUtil.maxLength(reply.toString().trim(), 300));
            return AiFixOutcome.repaired();
        } catch (BusinessException e) {
            log.warn("第 {}/{} 轮自愈因预算耗尽被拦截：{}", attempt, maxAttempts, e.getMessage());
            return AiFixOutcome.budgetExhausted(e.getMessage());
        } catch (Exception e) {
            String detail = describeAiFailure(e);
            if (isWorthRetrying(e)) {
                log.warn("第 {}/{} 轮自愈调用 AI 失败（基础设施类，值得再试）：{}",
                        attempt, maxAttempts, detail);
                return AiFixOutcome.notRepaired(detail);
            }
            // 确定性失败：同样输入必然得到同样结果，继续重试只会重复错误并让报告虚报「已自愈 N 轮」。
            log.error("第 {}/{} 轮自愈调用 AI 失败且重试无意义，将中止自愈循环：{}",
                    attempt, maxAttempts, detail, e);
            return AiFixOutcome.aborted(detail);
        }
    }

    /** 复用降级分类器判据：「换模型能否成功」在自愈里等价于「再试一次能否成功」。未识别异常默认不重试。 */
    boolean isWorthRetrying(Throwable e) {
        return FallbackFailureClassifier.shouldFallback(e);
    }

    private String describeAiFailure(Throwable e) {
        return FallbackFailureClassifier.describe(e);
    }

    /** 单轮修复结果：status 决定外层分支，reason 供日志与用户提示。 */
    private record AiFixOutcome(AiFixStatus status, String reason) {

        static AiFixOutcome repaired() {
            return new AiFixOutcome(AiFixStatus.REPAIRED, null);
        }

        static AiFixOutcome notRepaired(String reason) {
            return new AiFixOutcome(AiFixStatus.NOT_REPAIRED, reason);
        }

        static AiFixOutcome aborted(String reason) {
            return new AiFixOutcome(AiFixStatus.ABORTED, reason);
        }

        static AiFixOutcome budgetExhausted(String reason) {
            return new AiFixOutcome(AiFixStatus.BUDGET_EXHAUSTED, reason);
        }
    }

    private enum AiFixStatus {
        /** 模型完成了修复动作，不代表一定修好，需重新构建验证 */
        REPAIRED,
        NOT_REPAIRED,
        ABORTED,
        BUDGET_EXHAUSTED
    }

    /** user 消息里除报错外的固定字符数上界：修复指令必须留在 system 消息，上界才不随指令措辞变化。 */
    static final int PROMPT_OVERHEAD_RESERVE = 300;

    private String buildFailureReport(BuildResult result) {
        String template = loadPromptTemplate();
        String stage = StrUtil.blankToDefault(result.getStage(), "项目构建");
        return template
                .replace("{stage}", stage)
                .replace("{errorMessage}", resolveErrorMessage(result));
    }

    /** 无条件按 maxErrorLength 截断：配置项只在唯一一处收口才有意义，否则最常见的路径上根本不生效。 */
    private String resolveErrorMessage(BuildResult result) {
        int max = selfHealingProperties.getMaxErrorLength();
        String error = StrUtil.blankToDefault(
                result.getErrorMessage(),
                BuildErrorExtractor.extract(result.getRawOutput(), max));
        if (StrUtil.isBlank(error)) {
            return "构建命令返回非零退出码，但未能提取到具体错误信息，请检查项目结构完整性。";
        }
        if (error.length() > max) {
            log.warn("构建报错 {} 字，超过 maxErrorLength={}，已截断后再喂给模型",
                    error.length(), max);
            return error.substring(0, max) + "\n...(报错过长，已按 maxErrorLength 截断)";
        }
        return error;
    }

    /**
     * 缺失时退化为内置兜底文本；兜底文本必须不含修复指令，否则 {@link #PROMPT_OVERHEAD_RESERVE} 上界失效。
     */
    private String loadPromptTemplate() {
        try {
            return ResourceUtil.readStr(HEALING_PROMPT_RESOURCE, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取自愈提示词模板失败，使用内置兜底文本：{}", e.getMessage());
            return "刚才生成的项目构建失败了，请修复。\n\n失败阶段：{stage}\n\n"
                    + "错误输出：\n```\n{errorMessage}\n```\n";
        }
    }

    private String describeAttempt(int attempt, BuildResult result) {
        return String.format("第 %d 轮：%s —— %s", attempt,
                StrUtil.blankToDefault(result.getStage(), "构建"),
                StrUtil.maxLength(StrUtil.blankToDefault(result.getErrorMessage(), "未知错误"), 300));
    }

    private String buildFinalErrorMessage(int maxAttempts, List<String> attemptErrors, BuildResult last) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目构建失败，已自动尝试修复 ").append(maxAttempts).append(" 次仍未成功。\n\n");
        sb.append("各轮失败原因：\n");
        for (String line : attemptErrors) {
            sb.append("- ").append(line).append('\n');
        }
        if (StrUtil.isNotBlank(last.getErrorMessage())) {
            sb.append("\n最后一次错误：\n").append(last.getErrorMessage());
        }
        return sb.toString();
    }

    /** 须与「重试 N 次没修好」区分：中止是「没再试」，通常意味着模型一次都没调上。 */
    private String buildAbortedErrorMessage(String reason, List<String> attemptErrors, BuildResult last) {
        StringBuilder sb = new StringBuilder();
        sb.append("项目构建失败，自动修复已中止（未继续重试）。\n\n");
        sb.append("中止原因：").append(reason).append('\n');
        sb.append("说明：这是确定性失败，重试会得到同样的结果，因此不再消耗用户等待时间与调用额度。\n");
        if (!attemptErrors.isEmpty()) {
            sb.append("\n已尝试的修复轮次：\n");
            for (String line : attemptErrors) {
                sb.append("- ").append(line).append('\n');
            }
        }
        if (StrUtil.isNotBlank(last.getErrorMessage())) {
            sb.append("\n最后一次构建错误：\n").append(last.getErrorMessage());
        }
        return sb.toString();
    }

    private void emit(Long appId, String message) {
        if (appId == null) {
            return;
        }
        generationProgressRegistry.emit(appId, message);
    }
}
