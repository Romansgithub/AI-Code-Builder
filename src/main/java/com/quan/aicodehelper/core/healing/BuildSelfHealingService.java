package com.quan.aicodehelper.core.healing;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import com.quan.aicodehelper.ai.factory.AiCodeGeneratorServiceFactory;
import com.quan.aicodehelper.ai.service.AiCodeGeneratorService;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.config.SelfHealingProperties;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.core.builder.BuildErrorExtractor;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.builder.VueProjectBuilder;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
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
 * <p><b>怎么做</b>：把构建报错提取出来，作为对话消息追加给同一个 AI 会话
 * （复用 {@code @MemoryId appId} 带来的上下文，模型知道自己刚写了什么代码），
 * 让它用文件工具直接修改，然后重新构建。循环直到成功或用完重试次数。</p>
 *
 * <p><b>为什么不是无脑重试</b>：只有「代码问题」才重试。依赖装不上、目录不存在这类
 * 环境问题，重试多少次结果都一样，纯属浪费 token 和用户时间 —— 见
 * {@link com.quan.aicodehelper.model.enums.BuildFailureTypeEnum#isHealable()}。</p>
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
     * <p>必须在这里过闸：自愈是直接调 {@link AiCodeGeneratorService} 的，
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

            if (outcome == AiFixOutcome.BUDGET_EXHAUSTED) {
                // 预算耗尽既不是「修好了」也不是「修不动」：它是「没资格再试」。
                // 必须与「本轮没修好」区分开 —— 后者会 continue 进下一轮，
                // 而预算一旦耗尽，后续每一轮都会失败，等于把剩余重试次数
                // 在一次热循环里空转烧完，用户看到「一直在修复」却毫无进展。
                // 这里直接跳出循环，让构建失败的结果照常返回给调用方。
                log.warn("LLM 预算耗尽，中止构建自愈（已完成 {} 轮）", executedAttempts);
                emit(appId, "AI 调用额度已用尽，已停止自动修复，请手动检查构建错误");
                result.setErrorMessage(buildFinalErrorMessage(maxAttempts, attemptErrors, result));
                return HealingOutcome.of(result, false, executedAttempts - 1,
                        System.currentTimeMillis() - startedAt);
            }

            if (outcome == AiFixOutcome.NOT_REPAIRED) {
                log.warn("第 {} 轮自愈中，AI 未能给出有效修复", attempt);
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
     * <p>复用同一个 {@code appId} 对应的 AI 服务实例与对话记忆，
     * 因此模型能看到自己此前写入的文件内容，修复时不必重新读取全部上下文。</p>
     *
     * @return 修复尝试的结果。刻意用三态而非 boolean ——
     *         「AI 没修好，可以再试」与「没资格再试」必须区分，
     *         否则外层循环会把预算耗尽误当成前者而空转（见调用处注释）。
     */
    private AiFixOutcome askAiToFix(Long appId, BuildResult result, int attempt, int maxAttempts) {
        try {
            AiCodeGeneratorService service = aiCodeGeneratorServiceFactory
                    .getAiCodeGeneratorService(appId, CodeGenTypeEnum.VUE_PROJECT);

            String prompt = buildHealingPrompt(result);

            // 自愈直接调 service，不经过 Facade，所以在真正发起调用前单独过闸
            llmCallGuard.acquire("构建自愈-第" + attempt + "轮");

            // 同步消费整个流：自愈在独立虚拟线程中执行，此处阻塞不影响 SSE 主流程。
            // 模型的回复只会是一句简短说明，但工具调用过程可能较长。
            StringBuilder reply = new StringBuilder();
            service.generateVueProjectCodeStream(appId, prompt)
                    .doOnNext(reply::append)
                    .blockLast();

            log.info("第 {}/{} 轮自愈，AI 回复：{}", attempt, maxAttempts,
                    StrUtil.maxLength(reply.toString().trim(), 300));
            return AiFixOutcome.REPAIRED;
        } catch (BusinessException e) {
            // 预算耗尽：单独识别出来，交由调用方中止整个循环
            log.warn("第 {}/{} 轮自愈因预算耗尽被拦截：{}", attempt, maxAttempts, e.getMessage());
            return AiFixOutcome.BUDGET_EXHAUSTED;
        } catch (Exception e) {
            log.error("第 {}/{} 轮自愈调用 AI 失败：{}", attempt, maxAttempts, e.getMessage(), e);
            return AiFixOutcome.NOT_REPAIRED;
        }
    }

    /**
     * 单轮修复尝试的结果。
     */
    private enum AiFixOutcome {
        /** AI 完成了修复动作（不代表一定修好，需重新构建验证） */
        REPAIRED,
        /** AI 这一轮没能给出有效修复，可以再试 */
        NOT_REPAIRED,
        /** 预算耗尽，没有资格再调用 —— 必须中止循环 */
        BUDGET_EXHAUSTED
    }

    /**
     * 组装修复提示词。
     */
    private String buildHealingPrompt(BuildResult result) {
        String template = loadPromptTemplate();
        String stage = StrUtil.blankToDefault(result.getStage(), "项目构建");
        String errorMessage = StrUtil.blankToDefault(
                result.getErrorMessage(),
                BuildErrorExtractor.extract(result.getRawOutput(),
                        selfHealingProperties.getMaxErrorLength()));
        if (StrUtil.isBlank(errorMessage)) {
            errorMessage = "构建命令返回非零退出码，但未能提取到具体错误信息，请检查项目结构完整性。";
        }
        return template
                .replace("{stage}", stage)
                .replace("{errorMessage}", errorMessage);
    }

    /**
     * 读取提示词模板；文件缺失时退化为内置兜底文本，保证功能不因资源缺失而中断。
     */
    private String loadPromptTemplate() {
        try {
            return ResourceUtil.readStr(HEALING_PROMPT_RESOURCE, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("读取自愈提示词模板失败，使用内置兜底文本：{}", e.getMessage());
            return "你刚才生成的 Vue 项目构建失败了。\n\n失败阶段：{stage}\n\n错误输出：\n```\n{errorMessage}\n```\n\n"
                    + "请先用文件读取工具查看出错文件，然后做最小改动修复它，让 npm run build 能通过。"
                    + "修完后只输出一句简短说明并停止。";
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

    private void emit(Long appId, String message) {
        if (appId == null) {
            return;
        }
        generationProgressRegistry.emit(appId, message);
    }
}
