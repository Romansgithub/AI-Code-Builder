package com.quan.aicodehelper.core.eval;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
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
 * 评测跑批执行器：跑一遍评测集，产出可跨批次比较的指标。
 *
 * <p>不复用 chatToGenCode：其生成类型由 AI 路由决定，路由不稳会让指标失去可比性，故这里类型写死；
 * 但构建与自愈仍走生产的 {@link BuildSelfHealingService}。产物路径也只在生产侧算一次
 * （{@code {codeType}_{appId}}），评测侧仅换产物根，否则写盘方与读盘方各自算路径必然漂移。</p>
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
     * 跑一轮完整评测。用例串行执行：并发会让 npm install 互相抢 IO，
     * 耗时数据失真且日志混杂，评测关心数据可信而非速度。
     *
     * @return 跑批结果
     */
    public EvalRunResult run() {
        return run(null);
    }

    /**
     * 跑一轮评测，可只跑其中一条用例：跑批是真实调 AI + 真实 npm 构建，整集验证代价高，
     * 而改动往往只影响一条链路，这个开关把验证成本按改动范围切分。
     *
     * <p>它只影响跑哪些用例，不改指标口径：单跑时通过率分母变成 1（非注入）或 0（注入），
     * 因此单条数字不能与整集横向比较。</p>
     *
     * @param caseIdFilter 只跑这条用例；为空/空白表示跑全量
     * @return 跑批结果
     */
    public EvalRunResult run(String caseIdFilter) {
        if (!evalProperties.isEnabled()) {
            throw new com.quan.aicodehelper.exception.BusinessException(
                    com.quan.aicodehelper.exception.ErrorCode.SYSTEM_ERROR,
                    "评测跑批未启用，请设置 app.eval.enabled=true");
        }
        EvalSet evalSet = evalSetLoader.load();
        List<EvalCase> selectedCases = selectCases(evalSet, caseIdFilter);
        String batchId = "eval-" + IdUtil.fastSimpleUUID().substring(0, 8);

        EvalRunResult runResult = new EvalRunResult();
        runResult.setBatchId(batchId);
        runResult.setEvalSetName(evalSet.getName());
        runResult.setEvalSetVersion(evalSet.getVersion());
        long startedAt = System.currentTimeMillis();
        runResult.setStartedAt(startedAt);

        log.info("开始评测跑批 batchId={}，评测集「{}」v{}，共 {} 条用例{}",
                batchId, evalSet.getName(), evalSet.getVersion(), selectedCases.size(),
                StrUtil.isBlank(caseIdFilter) ? "" : "（已按 caseId 限定）");

        List<EvalRunResult.CaseResult> caseResults = new ArrayList<>();
        // 跑批期间把产物根切到 eval-run，跑完必须还原（AppConstant 是全局静态状态）。
        // evalRunRoot 只在此算一次：期间 AppConstant 已是 eval-run，再拼会得到 eval-run/eval-run/...
        String originalOutputRoot = AppConstant.getCodeOutputRootDir();
        String evalRunRoot = originalOutputRoot + File.separator + evalProperties.getOutputSubDir();
        AppConstant.Storage.apply(evalRunRoot, AppConstant.getCodeDeployRootDir(),
                AppConstant.getCodeDeployHost());
        try {
            for (EvalCase evalCase : selectedCases) {
                EvalRunResult.CaseResult cr = runSingleCase(evalCase, batchId, evalRunRoot);
                caseResults.add(cr);
                log.info("用例 {} 完成：首轮={} 自愈={}轮 最终={} 耗时={}ms",
                        evalCase.getId(), cr.isFirstBuildPassed(), cr.getHealingAttempts(),
                        cr.isFinalPassed(), cr.getTotalCostMs());
            }
        } finally {
            AppConstant.Storage.apply(originalOutputRoot, AppConstant.getCodeDeployRootDir(),
                    AppConstant.getCodeDeployHost());
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
     * 按 caseId 过滤出本轮要跑的用例。
     *
     * <p>找不到必须报错，不能静默跑 0 条：空报告看起来不像错误（无异常、无失败用例），
     * 会给出「验证过了」的错觉。报错时一并列出可用 caseId。</p>
     */
    private List<EvalCase> selectCases(EvalSet evalSet, String caseIdFilter) {
        List<EvalCase> allCases = evalSet.getCases();
        if (StrUtil.isBlank(caseIdFilter)) {
            return allCases;
        }
        List<EvalCase> matched = allCases.stream()
                .filter(c -> caseIdFilter.equals(c.getId()))
                .toList();
        if (matched.isEmpty()) {
            String available = allCases.stream()
                    .map(EvalCase::getId)
                    .collect(java.util.stream.Collectors.joining("、"));
            throw new com.quan.aicodehelper.exception.BusinessException(
                    com.quan.aicodehelper.exception.ErrorCode.PARAMS_ERROR,
                    "评测集里没有 caseId=" + caseIdFilter + " 的用例，可用用例：" + available);
        }
        log.info("本轮跑批限定用例：{}（原用例数 {}）", caseIdFilter, allCases.size());
        return matched;
    }

    /** 执行单条用例，异常在此收成「失败结果」而不上抛：一条用例挂掉不该让整轮跑批作废。 */
    private EvalRunResult.CaseResult runSingleCase(EvalCase evalCase, String batchId,
                                                   String evalRunRoot) {
        EvalRunResult.CaseResult cr = new EvalRunResult.CaseResult();
        cr.setCaseId(evalCase.getId());
        cr.setDescription(evalCase.getDescription());
        cr.setCodeGenType(evalCase.getCodeGenType().getValue());
        // 标记注入用例供汇总口径排除：它首轮必失败是设计使然，不该进一次通过率。
        cr.setFaultInjected(evalCase.getFaultInjection() != null);

        long startedAt = System.currentTimeMillis();
        // 负数 appId 作为评测会话 id：与真实应用 id 空间天然隔开，两次跑批也不共用对话记忆。
        Long evalAppId = nextEvalAppId();
        // 产物目录按生成侧命名规则算出，在生成之前清空一次
        String outputDir = prepareCaseOutputDir(evalCase, evalAppId, evalRunRoot);

        // 每条用例跑独立虚拟线程、主线程限时等待。目的是隔离而非提速：单条卡死不能拖垮整轮跑批。
        java.util.concurrent.atomic.AtomicReference<RuntimeException> asyncError =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = Thread.ofVirtual()
                .name("eval-case-" + evalCase.getId())
                .start(() -> EvalScope.runInScope(() -> {
                    try {
                        if (evalCase.getCodeGenType() == CodeGenTypeEnum.VUE_PROJECT) {
                            executeVueCase(evalCase, evalAppId, outputDir, cr);
                        } else {
                            executeSimpleCase(evalCase, evalAppId, outputDir, cr);
                        }
                    } catch (RuntimeException e) {
                        asyncError.set(e);
                    }
                }));

        int timeoutSeconds = evalProperties.getCaseTimeoutSeconds();
        try {
            worker.join(java.time.Duration.ofSeconds(timeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (cr.getGenerateCostMs() == 0L && cr.getBuildCostMs() == 0L) {
            // 无任何耗时数据，说明线程还没跑到计时点就卡住或已超时
            if (worker.isAlive()) {
                cr.setTimedOut(true);
                worker.interrupt();
                log.warn("用例 {} 超过 {} 秒仍未完成，按超时记失败", evalCase.getId(), timeoutSeconds);
            }
        } else if (worker.isAlive()) {
            // 已有部分耗时数据但线程仍在跑，同样按超时处理
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
        // 由执行器统一落库而非 Facade 埋点：跑批要把 batchId 一起写进去，而 Facade 处于生产热路径，不该知道「批次」。
        persistCaseMetric(cr, evalCase, batchId, evalAppId, outputDir);
        return cr;
    }

    /**
     * 执行 Vue 工程用例：先消费完整的生成流，再走自愈构建，拿到完整过程指标。
     */
    private void executeVueCase(EvalCase evalCase, Long evalAppId, String outputDir,
                                EvalRunResult.CaseResult cr) {
        // Vue 模式下模型通过文件工具直接写盘
        consumeGenerationStream(evalCase, evalAppId, cr);

        // 在「代码已落盘」与「开始构建」之间刻意破坏产物，让首轮必失败、自愈才有事可做；
        // 否则模型写得好时一次就过，自愈链路等于从未被验证。
        FaultInjectionOutcome injection = injectFaultIfConfigured(evalCase, outputDir);
        if (!injection.applied() && injection.configured()) {
            // 注入失败不能静默跳过：那样首轮多半直接构建通过，报告会显示漂亮的「一次通过」，
            // 而这条用例验证自愈的唯一意义随之消失。宁可判失败并写明原因。
            cr.setFirstBuildPassed(false);
            cr.setFinalPassed(false);
            cr.setHealingAttempts(0);
            cr.setBuildCostMs(0L);
            cr.setFailureType(BuildFailureTypeEnum.EXECUTION_ERROR.name());
            cr.setFailureMessage("故障注入失败：" + injection.reason()
                    + "（该用例靠注入制造必然失败来验证自愈，注入没生效则本轮数据无意义）");
            log.error("用例 {} 故障注入失败：{}", evalCase.getId(), injection.reason());
            return;
        }

        long buildStartedAt = System.currentTimeMillis();
        HealingOutcome outcome = buildSelfHealingService.buildWithHealingOutcome(outputDir, evalAppId);
        cr.setBuildCostMs(System.currentTimeMillis() - buildStartedAt);

        if (injection.applied() && outcome.isFirstBuildPassed()) {
            // 注入确实改坏了产物，首轮却通过，说明这处破坏没变成构建失败：要么目标文件不在构建图里，
            // 要么产物在注入后被别的并发流程改写回去了。此时「首轮通过」不是生成质量的证明，
            // 而是「这一轮根本没测到自愈」，故显式判失败。
            cr.setFirstBuildPassed(false);
            cr.setFinalPassed(false);
            cr.setHealingAttempts(0);
            cr.setFailureType(BuildFailureTypeEnum.EXECUTION_ERROR.name());
            cr.setFailureMessage("故障注入已应用，但首轮构建仍然通过：注入没有产生预期的构建失败，"
                    + "本轮的「一次通过」不代表生成质量，自愈也因此完全没被验证。"
                    + "请检查注入目标是否在构建图内、以及产物是否在注入后被其他并发流程改写。");
            log.error("用例 {} 注入已应用但首轮构建通过，判定本轮数据无意义", evalCase.getId());
            return;
        }

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
            // 统计项目根而非 dist：dist 是 vite 压缩产物，反映打包结果不是生成量；
            // 且 measureOutput 排除 node_modules 与 dist，传 dist 进去恒为 0。
            fillOutputSize(outputDir, cr);
        }
    }

    /**
     * 故障注入的执行结果。用对象而非 boolean，以区分「没配置注入」与「配置了但注入失败」。
     */
    private record FaultInjectionOutcome(boolean configured, boolean applied, String reason) {
        static FaultInjectionOutcome notConfigured() {
            return new FaultInjectionOutcome(false, false, null);
        }

        static FaultInjectionOutcome injected() {
            return new FaultInjectionOutcome(true, true, null);
        }

        static FaultInjectionOutcome failed(String reason) {
            return new FaultInjectionOutcome(true, false, reason);
        }
    }

    /**
     * 按用例配置破坏产物，制造一处确定性的、可自愈的构建失败。
     *
     * <p>只支持「整文件读、字符串替换、整文件写」一种形式：足够表达「把 import 路径改到不存在的
     * 目录」这类典型可自愈故障（表现为 {@code COMPILE_ERROR}，是
     * {@link BuildFailureTypeEnum#isHealable()} 里唯一为 true 的类型），又不至于把评测集变成脚本引擎。</p>
     */
    private FaultInjectionOutcome injectFaultIfConfigured(EvalCase evalCase, String outputDir) {
        EvalCase.FaultInjection fi = evalCase.getFaultInjection();
        if (fi == null) {
            return FaultInjectionOutcome.notConfigured();
        }

        File target;
        if (StrUtil.isNotBlank(fi.getFile())) {
            target = new File(outputDir, fi.getFile());
            if (!target.isFile()) {
                return FaultInjectionOutcome.failed(
                        "目标文件不存在：" + target.getAbsolutePath());
            }
        } else {
            // 未指定文件时自动定位，这是推荐用法：模型把组件 import 写在哪个文件并不稳定
            //（同一条 prompt 可能落在 App.vue 或 pages/Home.vue），写死会在模型换组织方式时失效。
            target = locateInjectableFile(outputDir, fi.getFind());
            if (target == null) {
                return FaultInjectionOutcome.failed(String.format(
                        "未在 %s 的 src 目录下任何 .vue/.js 文件中找到待替换内容「%s」，"
                                + "可能是模型输出的导入写法变了，请调整 find 串。该目录下的文件：%s",
                        outputDir, fi.getFind(), listSourceFiles(outputDir)));
            }
        }

        String content;
        try {
            content = FileUtil.readString(target, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return FaultInjectionOutcome.failed("读取目标文件失败：" + e.getMessage());
        }
        if (!content.contains(fi.getFind())) {
            // 找不到就报错而非静默跳过：它随模型输出风格漂移而失败，必须立刻让人去修 find 串。
            return FaultInjectionOutcome.failed(String.format(
                    "在 %s 中未找到待替换内容「%s」，可能是模型输出的导入写法变了，请调整 find 串",
                    target.getName(), fi.getFind()));
        }
        String broken = content.replace(fi.getFind(), fi.getReplace());
        try {
            FileUtil.writeString(broken, target, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return FaultInjectionOutcome.failed("写回目标文件失败：" + e.getMessage());
        }
        log.info("用例 {} 已注入故障：{} 中「{}」→「{}」",
                evalCase.getId(), target.getName(), fi.getFind(), fi.getReplace());
        return FaultInjectionOutcome.injected();
    }

    /**
     * 在产物的 src 目录下找出第一个包含 {@code find} 的源文件。
     *
     * <p>只扫 {@code src} 下的 {@code .vue} 与 {@code .js}：node_modules 体量大，全目录遍历既慢又会
     * 命中第三方包里的同名片段。返回 {@code null} 表示没找到，刻意不做兜底。</p>
     */
    private File locateInjectableFile(String outputDir, String find) {
        File srcDir = new File(outputDir, "src");
        if (!srcDir.isDirectory()) {
            return null;
        }
        for (File f : FileUtil.loopFiles(srcDir)) {
            if (!f.isFile()) {
                continue;
            }
            String name = f.getName().toLowerCase();
            if (!name.endsWith(".vue") && !name.endsWith(".js")) {
                continue;
            }
            try {
                if (FileUtil.readString(f, java.nio.charset.StandardCharsets.UTF_8).contains(find)) {
                    return f;
                }
            } catch (Exception e) {
                // 单个文件读失败不该让整次定位作废，跳过继续找
                log.debug("读取 {} 失败，跳过：{}", f.getAbsolutePath(), e.getMessage());
            }
        }
        return null;
    }

    /**
     * 列出 src 下的源文件，供注入失败时把实际文件名写进错误信息，省去翻磁盘猜与再跑一轮。
     */
    private String listSourceFiles(String outputDir) {
        File srcDir = new File(outputDir, "src");
        if (!srcDir.isDirectory()) {
            return "（src 目录不存在）";
        }
        List<String> names = new ArrayList<>();
        for (File f : FileUtil.loopFiles(srcDir)) {
            if (!f.isFile()) {
                continue;
            }
            String name = f.getName().toLowerCase();
            if (name.endsWith(".vue") || name.endsWith(".js")) {
                names.add(srcDir.toPath().relativize(f.toPath()).toString().replace('\\', '/'));
            }
        }
        return names.isEmpty() ? "（无 .vue/.js 文件）" : String.join(", ", names);
    }

    /**
     * 执行 HTML / 多文件用例：无构建步骤，最终通过等价于「解析保存成功」。
     *
     * <p>{@code outputDir} 由调用方传入而非在此现算：现算会连带清空目录，删掉刚落盘的代码。</p>
     */
    private void executeSimpleCase(EvalCase evalCase, Long evalAppId, String outputDir,
                                   EvalRunResult.CaseResult cr) {
        consumeGenerationStream(evalCase, evalAppId, cr);

        File dirFile = new File(outputDir);
        boolean saved = dirFile.isDirectory()
                && FileUtil.loopFiles(dirFile).stream().anyMatch(File::isFile);

        cr.setFirstBuildPassed(saved);
        cr.setFinalPassed(saved);
        cr.setHealingAttempts(0);
        cr.setBuildCostMs(0L);
        if (!saved) {
            cr.setFailureType(BuildFailureTypeEnum.MISSING_DIST.name());
            cr.setFailureMessage("代码未成功保存到 " + outputDir);
        } else {
            fillOutputSize(outputDir, cr);
        }
    }

    /**
     * 消费代码生成流并计时。{@code blockLast()} 跑到流结束（含流内保存回调），返回时代码已落盘。
     */
    private void consumeGenerationStream(EvalCase evalCase, Long evalAppId,
                                         EvalRunResult.CaseResult cr) {
        long startedAt = System.currentTimeMillis();
        Flux<String> flux = aiCodeGeneratorFacade.generateCodeStream(
                evalCase.getPrompt(), evalCase.getCodeGenType(), evalAppId);
        flux.blockLast();
        cr.setGenerateCostMs(System.currentTimeMillis() - startedAt);
    }

    /** 把用例结果写成一条评测记录，batchId 带上批次便于聚合查询。 */
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
     * 单条用例的产物目录，命名与生成侧一致的 {@code {codeType}_{appId}}。
     *
     * <p>只清理不创建：目录由生成侧自建，评测只保证它干净；清理须在生成之前、用例内只做一次，
     * 故由调用方在启动 worker 前显式调用。{@code evalRunRoot} 由外部传入而不现读
     * {@code AppConstant}，否则跑批期间会再拼一层目录。</p>
     */
    private String prepareCaseOutputDir(EvalCase evalCase, Long evalAppId, String evalRunRoot) {
        String dir = evalRunRoot + File.separator
                + evalCase.getCodeGenType().getValue() + "_" + evalAppId;
        // 清空杜绝跨轮残留：否则「这轮通过了」可能是上轮的 dist 还在，是评测里最隐蔽的假阳性。
        FileUtil.clean(dir);
        return dir;
    }

    private final java.util.concurrent.atomic.AtomicLong evalAppIdSeq =
            new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());

    /**
     * 生成评测用的负数 appId：与真实应用 id（自增正数）天然隔离，不会撞上真实应用，
     * 也让「这是评测数据」在库里一眼可辨。
     */
    private Long nextEvalAppId() {
        return -Math.abs(evalAppIdSeq.incrementAndGet());
    }

}
