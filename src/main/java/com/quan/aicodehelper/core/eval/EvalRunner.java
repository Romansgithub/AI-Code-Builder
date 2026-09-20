package com.quan.aicodehelper.core.eval;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.quan.aicodehelper.ai.facade.AiCodeGeneratorFacade;
import com.quan.aicodehelper.config.EvalProperties;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.healing.BuildSelfHealingService;
import com.quan.aicodehelper.core.healing.HealingOutcome;
import com.quan.aicodehelper.model.dto.eval.EvalCase;
import com.quan.aicodehelper.model.dto.eval.EvalSet;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测跑批执行器。
 *
 * <p><b>它解决什么问题</b>：单个功能好不好用可以靠感觉，但「改了自愈提示词之后效果变好了吗」
 * 这种问题只能靠固定用例反复跑、看数字变化来回答。这个类就是把评测集跑一遍，
 * 产出可对比的指标。</p>
 *
 * <p><b>为什么不复用 chatToGenCode</b>：那条路径要求先有 App 记录、要过权限校验，
 * 而且生成类型由 AI 路由决定 —— 路由不稳会让同一 prompt 落在不同类型上，
 * 指标失去可比性。所以这里直接驱动 {@link AiCodeGeneratorFacade}，
 * 类型由评测集写死。但构建与自愈仍然走生产同一条
 * {@link BuildSelfHealingService}，保证测的是真实链路而不是简化版。</p>
 *
 * <p><b>隔离性</b>：跑批产物写在独立的子目录下，不与用户真实产物混在一起，
 * 避免评测应用出现在用户的应用列表里。</p>
 */
@Slf4j
@Service
public class EvalRunner {

    @Resource
    private EvalProperties evalProperties;

    @Resource
    private EvalSetLoader evalSetLoader;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private BuildSelfHealingService buildSelfHealingService;

    @Resource
    private CodeGenEvalRecorder codeGenEvalRecorder;

    /**
     * 跑一轮完整评测。
     *
     * <p>同步执行、按用例顺序串行 —— 串行虽然慢，但并发跑会让 npm install
     * 互相抢 IO，耗时数据失真，且日志混在一起难以定位问题。
     * 评测关心的是数据可信，不是跑得快。</p>
     *
     * @return 跑批结果
     */
    public EvalRunResult run() {
        if (!evalProperties.isEnabled()) {
            throw new com.quan.aicodehelper.exception.BusinessException(
                    com.quan.aicodehelper.exception.ErrorCode.SYSTEM_ERROR,
                    "评测跑批未启用，请设置 app.eval.enabled=true");
        }
        EvalSet evalSet = evalSetLoader.load();
        String batchId = "eval-" + IdUtil.fastSimpleUUID().substring(0, 8);

        EvalRunResult runResult = new EvalRunResult();
        runResult.setBatchId(batchId);
        runResult.setEvalSetName(evalSet.getName());
        runResult.setEvalSetVersion(evalSet.getVersion());
        long startedAt = System.currentTimeMillis();
        runResult.setStartedAt(startedAt);

        log.info("开始评测跑批 batchId={}，评测集「{}」v{}，共 {} 条用例",
                batchId, evalSet.getName(), evalSet.getVersion(), evalSet.getCases().size());

        List<EvalRunResult.CaseResult> caseResults = new ArrayList<>();
        for (EvalCase evalCase : evalSet.getCases()) {
            EvalRunResult.CaseResult cr = runSingleCase(evalCase, batchId);
            caseResults.add(cr);
            log.info("用例 {} 完成：首轮={} 自愈={}轮 最终={} 耗时={}ms",
                    evalCase.getId(), cr.isFirstBuildPassed(), cr.getHealingAttempts(),
                    cr.isFinalPassed(), cr.getTotalCostMs());
        }

        runResult.setCases(caseResults);
        runResult.setSummary(EvalRunResult.summarize(caseResults));
        runResult.setTotalCostMs(System.currentTimeMillis() - startedAt);

        log.info("评测跑批 {} 结束：一次通过率 {}%，自愈成功率 {}%，总耗时 {}ms",
                batchId, runResult.getSummary().firstPassRate(),
                runResult.getSummary().healingSuccessRate(), runResult.getTotalCostMs());
        return runResult;
    }

    /**
     * 执行单条用例。
     *
     * <p>任何异常都在这里被收成「失败结果」而不是向上抛 —— 一条用例挂掉
     * 不该让整轮跑批作废，否则一个偶发的接口超时就得从头再跑二十分钟。</p>
     */
    private EvalRunResult.CaseResult runSingleCase(EvalCase evalCase, String batchId) {
        EvalRunResult.CaseResult cr = new EvalRunResult.CaseResult();
        cr.setCaseId(evalCase.getId());
        cr.setDescription(evalCase.getDescription());
        cr.setCodeGenType(evalCase.getCodeGenType().getValue());

        long startedAt = System.currentTimeMillis();
        // 负 appId 作为评测专用会话 id：与真实应用的 id 空间天然隔开，
        // 两次跑批之间也不会共用对话记忆（每批重新起号）
        Long evalAppId = nextEvalAppId();
        String outputDir = evalOutputDir(evalCase);

        // 每条用例跑在独立虚拟线程上，主线程限时等待。
        // 目的不是提速，而是隔离：单条用例卡死（比如模型反复自我修复不收敛）
        // 不能拖垮整轮跑批 —— 否则一次跑批就得从头再来。
        java.util.concurrent.atomic.AtomicReference<RuntimeException> asyncError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = Thread.ofVirtual()
                .name("eval-case-" + evalCase.getId())
                .start(() -> {
                    try {
                        if (evalCase.getCodeGenType() == CodeGenTypeEnum.VUE_PROJECT) {
                            executeVueCase(evalCase, evalAppId, outputDir, cr);
                        } else {
                            executeSimpleCase(evalCase, evalAppId, cr);
                        }
                    } catch (RuntimeException e) {
                        asyncError.set(e);
                    }
                });

        int timeoutSeconds = evalProperties.getCaseTimeoutSeconds();
        try {
            worker.join(java.time.Duration.ofSeconds(timeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (cr.getGenerateCostMs() == 0L && cr.getBuildCostMs() == 0L) {
            // 一刀未动说明线程还没跑到计时点就卡住了，或已超时
            if (worker.isAlive()) {
                cr.setTimedOut(true);
                worker.interrupt();
                log.warn("用例 {} 超过 {} 秒仍未完成，按超时记失败", evalCase.getId(), timeoutSeconds);
            }
        } else if (worker.isAlive()) {
            // 已有部分耗时数据但仍在跑，同样按超时处理
            cr.setTimedOut(true);
            worker.interrupt();
            log.warn("用例 {} 超过 {} 秒仍未结束，按超时记失败", evalCase.getId(), timeoutSeconds);
        }

        if (cr.isTimedOut()) {
            cr.setFinalPassed(false);
            cr.setFailureType(BuildFailureTypeEnum.TIMEOUT.name());
            cr.setFailureMessage(String.format("用例执行超过 %d 秒被判定为超时", timeoutSeconds));
        } else if (asyncError.get() != null) {
            RuntimeException e = asyncError.get();
            cr.setFinalPassed(false);
            cr.setFirstBuildPassed(false);
            cr.setUnexpectedError(e.getMessage());
            cr.setFailureType(BuildFailureTypeEnum.EXECUTION_ERROR.name());
            cr.setFailureMessage(e.getMessage());
            log.error("用例 {} 执行异常：{}", evalCase.getId(), e.getMessage(), e);
        }

        cr.setTotalCostMs(System.currentTimeMillis() - startedAt);
        // 统一由执行器落库：不用 Facade 的埋点，因为跑批需要把 batchId 一起写进去，
        // 而 Facade 处于生产热路径，不该知道「批次」这个概念。
        persistCaseMetric(cr, evalCase, batchId, evalAppId, outputDir);
        return cr;
    }

    /**
     * 执行 Vue 工程用例：直接调自愈构建，拿到完整过程指标。
     *
     * <p>与生产链路的差异只有一点：生产是先流式生成代码（模型通过工具写文件）
     * 再触发构建，这里同样如此 —— 先消费完整的生成流，再走自愈构建。</p>
     */
    private void executeVueCase(EvalCase evalCase, Long evalAppId, String outputDir,
                                EvalRunResult.CaseResult cr) {
        // 1) 消费代码生成流。Vue 模式下模型通过文件工具直接写盘，流里主要是过程性文本
        consumeGenerationStream(evalCase, evalAppId, cr);

        long buildStartedAt = System.currentTimeMillis();
        HealingOutcome outcome = buildSelfHealingService.buildWithHealingOutcome(outputDir, evalAppId);
        cr.setBuildCostMs(System.currentTimeMillis() - buildStartedAt);

        cr.setFirstBuildPassed(outcome.isFirstBuildPassed());
        cr.setHealingAttempts(outcome.getHealingAttempts());
        cr.setFinalPassed(outcome.isFinalPassed());

        BuildResult buildResult = outcome.getBuildResult();
        if (!outcome.isFinalPassed() && buildResult != null) {
            cr.setFailureType(buildResult.getFailureType() == null
                    ? null : buildResult.getFailureType().name());
            cr.setFailureMessage(buildResult.getErrorMessage());
        }
        if (outcome.isFinalPassed()) {
            fillOutputSize(new File(outputDir, "dist").getAbsolutePath(), cr);
        }
    }

    /**
     * 执行 HTML / 多文件用例：没有构建步骤，最终通过等价于「解析保存成功」。
     */
    private void executeSimpleCase(EvalCase evalCase, Long evalAppId,
                                   EvalRunResult.CaseResult cr) {
        consumeGenerationStream(evalCase, evalAppId, cr);

        String dir = evalOutputDir(evalCase);
        File dirFile = new File(dir);
        boolean saved = dirFile.isDirectory()
                && FileUtil.loopFiles(dirFile).stream().anyMatch(File::isFile);

        cr.setFirstBuildPassed(saved);
        cr.setFinalPassed(saved);
        cr.setHealingAttempts(0);
        cr.setBuildCostMs(0L);
        if (!saved) {
            cr.setFailureType(BuildFailureTypeEnum.MISSING_DIST.name());
            cr.setFailureMessage("代码未成功保存到 " + dir);
        } else {
            fillOutputSize(dir, cr);
        }
    }

    /**
     * 消费代码生成流并计时。
     *
     * <p>{@code blockLast()} 会跑到流结束（含流内的保存回调），因此这条语句返回时
     * 代码已经落盘。</p>
     */
    private void consumeGenerationStream(EvalCase evalCase, Long evalAppId,
                                         EvalRunResult.CaseResult cr) {
        long startedAt = System.currentTimeMillis();
        Flux<String> flux = aiCodeGeneratorFacade.generateCodeStream(
                evalCase.getPrompt(), evalCase.getCodeGenType(), evalAppId);
        flux.blockLast();
        cr.setGenerateCostMs(System.currentTimeMillis() - startedAt);
    }

    /**
     * 把用例结果写成一条评测记录，batchId 带上批次便于聚合查询。
     */
    private void persistCaseMetric(EvalRunResult.CaseResult cr, EvalCase evalCase,
                                   String batchId, Long evalAppId, String outputDir) {
        try {
            GenerationMetric metric = GenerationMetric.builder()
                    .appId(evalAppId)
                    .batchId(batchId)
                    .userPrompt(evalCase.getPrompt())
                    .codeGenType(evalCase.getCodeGenType().getValue())
                    .firstBuildPassed(cr.isFirstBuildPassed())
                    .finalPassed(cr.isFinalPassed())
                    .healingAttempts(cr.getHealingAttempts())
                    .generateCostMs(cr.getGenerateCostMs())
                    .buildCostMs(cr.getBuildCostMs())
                    .totalCostMs(cr.getTotalCostMs())
                    .failureType(cr.getFailureType())
                    .failureMessage(cr.getFailureMessage())
                    .build();
            if (cr.getFileCount() != null) {
                metric.setFileCount(cr.getFileCount());
                metric.setTotalBytes(cr.getTotalBytes());
            }
            codeGenEvalRecorder.record(metric);
        } catch (Exception e) {
            log.warn("落库评测用例结果失败（不影响跑批）：{}", e.getMessage());
        }
    }

    private void fillOutputSize(String dir, EvalRunResult.CaseResult cr) {
        long[] size = CodeGenEvalRecorder.measureOutput(dir);
        cr.setFileCount((int) size[0]);
        cr.setTotalBytes(size[1]);
    }

    /**
     * 评测产物目录：code-output-dir/eval-run/&lt;批次无关的用例 id&gt;。
     *
     * <p>用用例 id 而非 appId 命名：重跑同一用例时目录会先清空重建，
     * 保证不会读到上一轮的残留产物 —— 否则「这轮通过了」可能是上轮的
     * dist 还在，这是评测里最隐蔽的一类假阳性。</p>
     */
    private String evalOutputDir(EvalCase evalCase) {
        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                + evalProperties.getOutputSubDir() + File.separator
                + evalCase.getCodeGenType().getValue() + "_" + evalCase.getId();
        // 清空重建，杜绝跨轮次残留
        FileUtil.clean(dir);
        FileUtil.mkdir(dir);
        return dir;
    }

    private final java.util.concurrent.atomic.AtomicLong evalAppIdSeq =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    /**
     * 生成评测用的负数 appId。
     *
     * <p>负数与真实应用 id（自增正数）天然隔离：既不会撞上真实应用，
     * 也让「这是评测数据」在库里一眼可辨。</p>
     */
    private Long nextEvalAppId() {
        return -Math.abs(evalAppIdSeq.incrementAndGet());
    }

}
