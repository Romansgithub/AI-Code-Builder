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
 *
 * <p><b>产物路径契约（重要，别再改回去）</b>：生成侧落盘目录由
 * {@code FileWriteTool} / {@code CodeFileSaverTemplate} 按
 * {@code {codeType}_{appId}} 拼出，这个 appId 是生成侧自己收到的那个。
 * 评测侧只在跑批期间把 {@link AppConstant.Storage} 的产物根指向
 * {@code code-output-dir/eval-run}，<b>然后完全复用同一套命名规则</b>去读产物。</p>
 *
 * <p>历史上这里踩过一个坑：评测侧曾用「用例 id」自造了第三套目录名
 * （{@code eval-run/vue_project_vue-todo}），而生成侧实际落在
 * {@code eval-run/vue_project_-1789915935607} —— 两条路径永远对不上，
 * 8 条用例全被判失败，日志里却明明白白写着构建成功。
 * <b>凡是「写盘的一方」和「读盘的一方」分别算路径，就一定会漂移。</b>
 * 现在只有一处算路径（生产那套），评测侧只换根目录。</p>
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
        // 整轮跑批期间把产物根切到 eval-run 子目录，跑完必须还原：
        // AppConstant 是全局静态状态，不还原会让评测产物污染之后所有真实用户的生成。
        //
        // 关键：evalRunRoot 只在此处算一次并向下传递，绝不能在跑批过程中再调
        // evalOutputRoot() —— 那时 AppConstant 已经是 eval-run 了，再拼一次会得到
        // eval-run/eval-run/...（这个坑第一次实现时就踩到了，契约测试当场抓出）。
        String originalOutputRoot = AppConstant.getCodeOutputRootDir();
        String evalRunRoot = originalOutputRoot + File.separator + evalProperties.getOutputSubDir();
        AppConstant.Storage.apply(evalRunRoot, AppConstant.getCodeDeployRootDir(),
                AppConstant.getCodeDeployHost());
        try {
            for (EvalCase evalCase : evalSet.getCases()) {
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
     * 执行单条用例。
     *
     * <p>任何异常都在这里被收成「失败结果」而不是向上抛 —— 一条用例挂掉
     * 不该让整轮跑批作废，否则一个偶发的接口超时就得从头再跑二十分钟。</p>
     */
    private EvalRunResult.CaseResult runSingleCase(EvalCase evalCase, String batchId,
                                                   String evalRunRoot) {
        EvalRunResult.CaseResult cr = new EvalRunResult.CaseResult();
        cr.setCaseId(evalCase.getId());
        cr.setDescription(evalCase.getDescription());
        cr.setCodeGenType(evalCase.getCodeGenType().getValue());
        // 标出来让汇总口径能排除它：注入用例首轮必失败是我们的设计，
        // 不该出现在「一次通过率」里（口径实现在 EvalRunResult.summarize）。
        cr.setFaultInjected(evalCase.getFaultInjection() != null);

        long startedAt = System.currentTimeMillis();
        // 负 appId 作为评测专用会话 id：与真实应用的 id 空间天然隔开，
        // 两次跑批之间也不会共用对话记忆（每批重新起号）
        Long evalAppId = nextEvalAppId();
        // 产物目录按生成侧的命名规则算出，并在此刻清空（只清一次，且在生成之前）
        String outputDir = prepareCaseOutputDir(evalCase, evalAppId, evalRunRoot);

        // 每条用例跑在独立虚拟线程上，主线程限时等待。
        // 目的不是提速，而是隔离：单条用例卡死（比如模型反复自我修复不收敛）
        // 不能拖垮整轮跑批 —— 否则一次跑批就得从头再来。
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

        // 2) 故障注入：在「代码已落盘」与「开始构建」之间刻意破坏一处产物。
        // 这样首轮构建必然失败，自愈才有事可做 —— 否则模型写得好时一次就过，
        // 「自愈成功率」永远是「本次无用例触发自愈」，整条链路等于没被验证过。
        FaultInjectionOutcome injection = injectFaultIfConfigured(evalCase, outputDir);
        if (!injection.applied() && injection.configured()) {
            // 注入失败绝不能静默跳过：跳过后首轮很可能直接构建通过，
            // 报告上显示一条「一次通过」的漂亮数据，而这条用例存在的唯一意义
            // （验证自愈）悄然消失了。宁可把这条判成失败并写明原因。
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
            // 统计项目根而不是 dist：要衡量的是「AI 写了多少代码」，
            // 而 dist 是 vite 压缩后的构建产物，大小反映的是打包结果不是生成量。
            // 且 measureOutput 本身会排除 node_modules 与 dist，
            // 传 dist 进去会让每个文件都命中排除规则 → 恒为 0（这个坑踩过一次）。
            fillOutputSize(outputDir, cr);
        }
    }

    /**
     * 故障注入的执行结果。
     *
     * <p>用一个小对象而不是 boolean，是为了让「没配置注入」和「配置了但注入失败」
     * 能被区分开 —— 前者是正常流程，后者是必须暴露的异常。</p>
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
     * <p>只支持「整文件读 → 字符串替换 → 整文件写」这一种形式：足够表达
     * 「把 import 路径改到不存在的目录」这类典型可自愈故障（会以
     * {@code Could not resolve} 形式表现为 {@code COMPILE_ERROR}，
     * 而这是 {@link BuildFailureTypeEnum#isHealable()} 里唯一为 true 的类型），
     * 又不至于把评测集变成一个脚本引擎。</p>
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
            // 未指定文件时自动定位。这是**推荐用法**，因为模型把组件 import 写在哪个
            // 文件里并不稳定：同一条 prompt 历次跑批可能落在 App.vue、pages/Home.vue、
            // pages/HomePage.vue。写死任一个都会在模型换一种组织方式时失效，
            // 而失效的表现是一条「首轮就通过」的漂亮假数据（若静默跳过的话）。
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
            // 找不到就报错而不是静默跳过。这一条会随模型输出风格漂移而命中失败，
            // 一旦命中失败必须让人立刻知道并去修 find 串，而不是继续产出假数据。
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
     * <p>扫描范围限定在 {@code src} 下、只认 {@code .vue} 与 {@code .js}：
     * 产物的 {@code node_modules} 体量很大，全目录遍历既慢又可能命中第三方包里的
     * 同名片段 —— 那样注入的是依赖而不是模型写的代码，构建失败的原因就跑偏了。</p>
     *
     * <p>返回 {@code null} 表示没找到，由调用方报错。这里刻意不做「就近取一个文件」
     * 之类的兜底：注入到不含目标串的文件上等于什么都没做，而那样又会退化成
     * 「首轮直接通过」的假数据。</p>
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
     * 列出 src 下的源文件，用于注入失败时把「实际有哪些文件」写进错误信息。
     *
     * <p>否则拿到「找不到 xxx」只能靠翻磁盘猜，下一轮照样要再跑一次二十分钟的跑批
     * 才能定位 —— 一次跑批的成本远高于多打这一行日志。</p>
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
     * 执行 HTML / 多文件用例：没有构建步骤，最终通过等价于「解析保存成功」。
     *
     * <p>{@code outputDir} 由调用方传入而不再在这里现算 —— 现算的那版会连带
     * 执行一次目录清空，把上一步刚落盘的代码删掉。</p>
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
     * 单条用例的产物目录：与生成侧完全一致的 {@code {codeType}_{appId}} 命名。
     *
     * <p><b>这里只负责清理，不负责创建</b>：目录由生成侧（文件工具 / 保存器）自己建，
     * 评测只保证它是干净的。之所以强调这点 —— 早先的实现里「读产物」这一步
     * 顺手调了一次带 {@code FileUtil.clean} 的路径计算，等于把刚落盘的代码清空，
     * 是个必然踩中的雷。</p>
     *
     * <p>清理必须在生成之前做，且用例内只做一次，所以由调用方在启动 worker
     * 之前显式调用。</p>
     *
     * <p>注意 {@code evalRunRoot} 是外部传入的固定值，不从 {@code AppConstant} 现读 ——
     * 跑批期间 AppConstant 已被换成同一个值，现读会再拼一层目录。</p>
     */
    private String prepareCaseOutputDir(EvalCase evalCase, Long evalAppId, String evalRunRoot) {
        String dir = evalRunRoot + File.separator
                + evalCase.getCodeGenType().getValue() + "_" + evalAppId;
        // 清空重建，杜绝跨轮次残留 —— 否则「这轮通过了」可能是上轮的 dist 还在，
        // 这是评测里最隐蔽的一类假阳性。
        FileUtil.clean(dir);
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
