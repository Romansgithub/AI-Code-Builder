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
 * 构建自愈服务。
 *
 * <p><b>解决什么问题</b>：AI 生成的 Vue 项目经常因为 import 路径、标签闭合、CSS 语法等
 * 小问题构建失败。早期实现只是把错误打到日志、告诉用户「构建失败」，
 * 用户要么自己改代码，要么重新生成碰运气。</p>
 *
 * <p><b>怎么做</b>：把构建报错提取出来，作为 user 消息追加给同一个 AI 会话
 * （复用 {@code @MemoryId appId} 带来的上下文，模型知道自己刚写了什么代码），
 * 让它用文件工具直接修改，然后重新构建。循环直到成功或用完重试次数。</p>
 *
 * <p><b>为什么走独立的自愈服务，而不是复用生成服务</b>：生成服务的 user 消息来自聊天框，
 * 因此挂了输入护栏；自愈的 user 消息是程序拼装的构建报错，被那道护栏拦下过一次
 * （报错 573 字 + 模板 668 字 &gt; 1000 字上限），结果是「自愈两轮」零耗时、
 * 模型一次都没被调用过。完整记录见 {@link AiCodeHealingService}。</p>
 *
 * <p><b>为什么不是无脑重试</b>：只有「代码问题」才重试。依赖装不上、目录不存在这类
 * 环境问题，重试多少次结果都一样，纯属浪费 token 和用户时间 —— 见
 * {@link com.quan.aicodehelper.model.enums.BuildFailureTypeEnum#isHealable()}。
 * 同一条原则也适用于「调用本身失败」：确定性失败（输入校验、参数非法、未识别的异常）
 * 重试必然得到同样结果，必须立即中止而不是 continue。</p>
 */
@Slf4j
@Service
public class BuildSelfHealingService {

    /**
     * 修复提示词模板路径。
     */
    private static final String HEALING_PROMPT_RESOURCE = "prompt/build-self-healing-prompt.txt";

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;

    @Resource
    private SelfHealingProperties selfHealingProperties;

    @Resource
    private GenerationProgressRegistry generationProgressRegistry;

    /**
     * LLM 调用预算闸门。
     *
     * <p>必须在这里过闸：自愈是直接调 {@link AiCodeHealingService} 的，
     * 并不经过 {@code AiCodeGeneratorFacade}。而自愈恰恰是**最贵**的一条路径
     * ——一次生成失败最多触发 maxAttempts 轮完整生成，每轮都是足量输入输出。
     * 漏掉这里的后果就是「预算显示没用到，实际已经花了几倍」。</p>
     */
    @Resource
    private LlmCallGuard llmCallGuard;

    /**
     * 带自愈的构建。
     *
     * <p>流程：构建 → 失败且可自愈 → 让 AI 修 → 再构建，最多重试
     * {@link SelfHealingProperties#getMaxAttempts()} 轮。</p>
     *
     * @param projectPath 项目目录
     * @param appId       应用 ID（同时作为 AI 会话记忆 ID）
     * @return 最终构建结果。自愈成功时返回成功结果；用尽重试仍失败时，
     *         返回最后一次的失败结果，并附上尝试次数
     */
    public BuildResult buildWithHealing(String projectPath, Long appId) {
        return buildWithHealingOutcome(projectPath, appId).getBuildResult();
    }

    /**
     * 带自愈的构建，并返回完整过程指标。
     *
     * <p>相比 {@link #buildWithHealing}，额外回报「首次是否通过」与「自愈轮数」，
     * 供评测模块统计一次通过率与自愈成功率。</p>
     *
     * @param projectPath 项目目录
     * @param appId       应用 ID（同时作为 AI 会话记忆 ID）
     * @return 自愈结果，含最终构建结果与过程指标
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
                // 预算耗尽既不是「修好了」也不是「修不动」：它是「没资格再试」。
                // 必须与「本轮没修好」区分开 —— 后者会 continue 进下一轮，
                // 而预算一旦耗尽，后续每一轮都会失败，等于把剩余重试次数
                // 在一次热循环里空转烧完，用户看到「一直在修复」却毫无进展。
                // 这里直接跳出循环，让构建失败的结果照常返回给调用方。
                log.warn("LLM 预算耗尽，中止构建自愈（已完成 {} 轮）", executedAttempts);
                emit(appId, "AI 调用额度已用尽，已停止自动修复，请手动检查构建错误");
                result.setErrorMessage(buildFinalErrorMessage(maxAttempts, attemptErrors, result));
                return HealingOutcome.of(result, false, executedAttempts,
                        System.currentTimeMillis() - startedAt);
            }

            if (outcome.status() == AiFixStatus.ABORTED) {
                // 中止 ≠ 这轮没修好。确定性失败再试一轮只会得到同样的结果，
                // 那正是本项目自称要避免的「无脑重试」——而且代价很实在：
                // 用户多等一轮、日志里多几条正常的重试提示、报告里多两轮假的修复记录。
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
                // 继续下一轮：模型没改成功，但错误信息还在，可以再试
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
     * 让 AI 针对构建错误进行修复。
     *
     * <p>调用的是<b>自愈专用</b>的 AI 服务：它复用同一个 {@code appId} 的对话记忆
     * （模型能看到自己此前写入的文件内容），但<b>不挂用户输入护栏</b> ——
     * 护栏那套「输入不超过 1000 字」的规则针对的是聊天框，套在构建报错上
     * 会把请求直接拒掉。详见 {@link AiCodeHealingService}。</p>
     *
     * @return 修复尝试的结果。刻意用四态而非 boolean ——「AI 改了但没改对（可再试）」、
     *         「没资格再试（预算耗尽）」、「再试也没用（确定性失败）」三者
     *         在外层是三条不同的分支，压成一个 false 就会退化成无脑重试。
     */
    private AiFixOutcome askAiToFix(Long appId, BuildResult result, int attempt, int maxAttempts) {
        try {
            AiCodeHealingService healingService =
                    aiCodeGeneratorServiceFactory.getAiCodeHealingService(appId);

            String report = buildFailureReport(result);

            // 自愈直连 service，不经过 Facade，所以在真正发起调用前单独过闸
            llmCallGuard.acquire("构建自愈-第" + attempt + "轮");

            // 同步消费整个流：自愈在独立虚拟线程中执行，此处阻塞不影响 SSE 主流程。
            // 模型的回复只会是一句简短说明，但工具调用过程可能较长。
            StringBuilder reply = new StringBuilder();
            healingService.healVueProjectBuildFailure(appId, report)
                    .doOnNext(reply::append)
                    .blockLast();

            log.info("第 {}/{} 轮自愈，AI 回复：{}", attempt, maxAttempts,
                    StrUtil.maxLength(reply.toString().trim(), 300));
            return AiFixOutcome.repaired();
        } catch (BusinessException e) {
            // 这道闸门是本次调用里唯一会抛 BusinessException 的地方
            log.warn("第 {}/{} 轮自愈因预算耗尽被拦截：{}", attempt, maxAttempts, e.getMessage());
            return AiFixOutcome.budgetExhausted(e.getMessage());
        } catch (Exception e) {
            String detail = describeAiFailure(e);
            if (isWorthRetrying(e)) {
                log.warn("第 {}/{} 轮自愈调用 AI 失败（基础设施类，值得再试）：{}",
                        attempt, maxAttempts, detail);
                return AiFixOutcome.notRepaired(detail);
            }
            // 确定性失败：同样的输入必然得到同样的结果。继续下一轮只是把这个错误
            // 再演一遍 —— 而且会掩盖真相：报告上会显示「自愈 N 轮」，看起来努力过了，
            // 实际上模型一次都没被调用（这正是 2026-09-21 那次跑批的真实情形）。
            log.error("第 {}/{} 轮自愈调用 AI 失败且重试无意义，将中止自愈循环：{}",
                    attempt, maxAttempts, detail, e);
            return AiFixOutcome.aborted(detail);
        }
    }

    /**
     * 这次 AI 调用失败是否值得再试一轮。
     *
     * <p>判据直接复用模型降级的分类器：它对「换个模型 / 换个 key 是否可能成功」的回答，
     * 在自愈这里恰好等价于「再试一次是否可能成功」。共用一份判据，是为了让
     * 「重试之前先问重试能不能让它变好」这条原则在两条链路上不会各写一套后慢慢跑偏。</p>
     *
     * <p>注意它的默认结论是<b>不重试</b>：未识别的异常类型一律视为确定性失败。
     * 这与「宁可让错误如实暴露，也不要因为看不懂就多打一次调用」一致。</p>
     */
    boolean isWorthRetrying(Throwable e) {
        return FallbackFailureClassifier.shouldFallback(e);
    }

    /**
     * 把异常翻译成一句能进日志和用户提示的说明。
     */
    private String describeAiFailure(Throwable e) {
        return FallbackFailureClassifier.describe(e);
    }

    /**
     * 单轮修复尝试的结果。
     *
     * <p>用 record 而不是 boolean：status 决定外层走哪条分支，reason 决定
     * 「为什么」能被写进日志与用户提示 —— 否则一旦中止，运维只能看到
     * 「修复失败」四个字，无从判断是模型改不动还是压根没调上。</p>
     */
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

    /**
     * 单轮修复尝试的三种结局。
     */
    private enum AiFixStatus {
        /** 模型完成了修复动作（不代表一定修好，需重新构建验证） */
        REPAIRED,
        /** 这一轮没生效，但属可重试的失败，值得再试一轮 */
        NOT_REPAIRED,
        /** 确定性失败：重试会得到同样的结果，必须立即中止循环 */
        ABORTED,
        /** 预算耗尽，没有资格再调用 —— 必须中止循环 */
        BUDGET_EXHAUSTED
    }

    /**
     * user 消息里除报错正文之外的固定字符数上界（模板 + 截断标记）。
     *
     * <p>被测试用来锁住一条设计约束：<b>修复指令必须留在 system 消息里</b>。
     * user 消息只装构建报错，长度因此只随报错增长，不随指令措辞变化。
     * 一旦有人把成段的指令拼回 user 消息，这个上界就会被突破、测试立刻失败 ——
     * 而这类改动在 code review 时几乎看不出来，只会表现为「提示词又变长了」。</p>
     */
    static final int PROMPT_OVERHEAD_RESERVE = 300;

    /**
     * 组装自愈请求的 user 消息。
     *
     * <p>这里<b>只放构建失败信息</b>：「怎么修」的要求在 system 消息里
     * （{@code prompt/build-self-healing-system-prompt.txt}）。这样拆不只是语义更对
     * （指令是规则，不是用户的诉求），也是长度上界成立的前提。</p>
     */
    private String buildFailureReport(BuildResult result) {
        String template = loadPromptTemplate();
        String stage = StrUtil.blankToDefault(result.getStage(), "项目构建");
        return template
                .replace("{stage}", stage)
                .replace("{errorMessage}", resolveErrorMessage(result));
    }

    /**
     * 取要喂给模型的报错文本，并<b>无条件</b>按 {@code maxErrorLength} 截断。
     *
     * <p>为什么强调「无条件」：原先只有走 {@link BuildErrorExtractor} 兜底时
     * 才把长度上限传进去，而 {@link BuildResult#getErrorMessage()} 是构建器用
     * <b>不带上限</b>的 {@code BuildErrorExtractor.extract(rawOutput)} 提取的 ——
     * 也就是说 {@code app.self-healing.max-error-length} 这条配置在最常见的路径上
     * <b>根本没生效</b>。一个配置项只有在唯一一处收口才有意义，所以统一到这里截断。</p>
     */
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
     * 读取 user 消息模板；文件缺失时退化为内置兜底文本，保证功能不因资源缺失而中断。
     *
     * <p>兜底文本必须与文件保持一致：<b>只含失败信息，不含修复指令</b>
     * （指令在 system 消息里）。若这里顺手把指令也塞进来，就等于给
     * 「user 消息长度」开了一个随模板变化的缺口，
     * {@link #PROMPT_OVERHEAD_RESERVE} 这个上界也就不再成立了。</p>
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

    /**
     * 用尽重试后，把每轮的错误拼进最终信息，便于用户排查。
     */
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

    /**
     * 自愈被中止时的最终错误信息。
     *
     * <p>必须与「重试了 N 次都没修好」说清楚区别：前者是「没有再试」，
     * 后者是「试过了不行」。混成一句「修复失败」会让人误以为模型努力过 ——
     * 而这类中止通常恰恰意味着模型一次都没被调上，是最需要立刻排查的情形。</p>
     */
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
