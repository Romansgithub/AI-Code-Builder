package com.quan.aicodehelper.ai.facade;

import com.quan.aicodehelper.ai.factory.AiCodeGeneratorServiceFactory;
import com.quan.aicodehelper.ai.service.AiCodeGeneratorService;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.config.LlmBudgetProperties;
import com.quan.aicodehelper.core.budget.LlmCallGuard;
import com.quan.aicodehelper.core.builder.BuildResult;
import com.quan.aicodehelper.core.eval.CodeGenEvalRecorder;
import com.quan.aicodehelper.core.eval.EvalScope;
import com.quan.aicodehelper.core.eval.GenerationMetric;
import com.quan.aicodehelper.core.healing.BuildSelfHealingService;
import com.quan.aicodehelper.core.healing.HealingOutcome;
import com.quan.aicodehelper.core.rag.CodeContextProvider;
import com.quan.aicodehelper.core.rag.CodeIndexService;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成评测埋点测试：指标要与真实过程一致，且埋点异常不能反噬主流程。
 */
@DisplayName("代码生成评测埋点")
class AiCodeGeneratorFacadeEvalTest {

    private static final Long APP_ID = 2001L;
    private static final String USER_MESSAGE = "做一个登录页";

    private AiCodeGeneratorServiceFactory serviceFactory;
    private AiCodeGeneratorService aiService;
    private BuildSelfHealingService healingService;
    private GenerationProgressRegistry progressRegistry;
    private CodeGenEvalRecorder recorder;
    private CodeContextProvider codeContextProvider;
    private CodeIndexService codeIndexService;
    private AiCodeGeneratorFacade facade;

    /** 用并发集合是因为 Vue 分支在虚拟线程里上报 */
    private final List<GenerationMetric> captured = new CopyOnWriteArrayList<>();

    private Path outputRoot;

    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        serviceFactory = mock(AiCodeGeneratorServiceFactory.class);
        aiService = mock(AiCodeGeneratorService.class);
        healingService = mock(BuildSelfHealingService.class);
        progressRegistry = mock(GenerationProgressRegistry.class);
        recorder = mock(CodeGenEvalRecorder.class);
        // 检索增强与索引是旁路能力，契约由 core/rag 下的测试单独覆盖
        codeContextProvider = mock(CodeContextProvider.class);
        codeIndexService = mock(CodeIndexService.class);
        when(codeContextProvider.buildContext(anyLong(), any(), anyString())).thenReturn("");

        when(serviceFactory.getAiCodeGeneratorService(anyLong(), any())).thenReturn(aiService);
        // Vue 分支流内容不参与断言；不补桩会因 codeStream 为 null 报 NPE
        when(aiService.generateVueProjectCodeStream(anyLong(), anyString(), anyString())).thenReturn(Flux.empty());
        when(aiService.generateMultiFileCodeStream(anyLong(), anyString(), anyString())).thenReturn(Flux.empty());

        // 捕获所有上报，便于统一检查「有且只有一条」
        org.mockito.Mockito.doAnswer(inv -> {
            captured.add(inv.getArgument(0));
            return null;
        }).when(recorder).record(any(GenerationMetric.class));

        // 沙箱化的输出目录：不能污染项目真实的 tmp/code_output
        tempDir = Files.createTempDirectory("facade-eval-test");
        outputRoot = tempDir.resolve("code_output");
        Files.createDirectories(outputRoot);
        AppConstant.Storage.apply(outputRoot.toString(), tempDir.resolve("code_deploy").toString(),
                "http://localhost:8080/api/static/");

        facade = new AiCodeGeneratorFacade();
        ReflectionTestUtils.setField(facade, "aiCodeGeneratorServiceFactory", serviceFactory);
        ReflectionTestUtils.setField(facade, "buildSelfHealingService", healingService);
        ReflectionTestUtils.setField(facade, "generationProgressRegistry", progressRegistry);
        ReflectionTestUtils.setField(facade, "codeGenEvalRecorder", recorder);
        ReflectionTestUtils.setField(facade, "codeContextProvider", codeContextProvider);
        ReflectionTestUtils.setField(facade, "codeIndexService", codeIndexService);
        // 上限充足的真实闸门；预算行为由 LlmCallGuardTest 单独覆盖
        LlmBudgetProperties budgetProperties = new LlmBudgetProperties();
        budgetProperties.setMaxCalls(10_000);
        ReflectionTestUtils.setField(facade, "llmCallGuard", new LlmCallGuard(budgetProperties));
    }

    @AfterEach
    void tearDown() {
        // 还原全局静态状态，避免影响同 JVM 内其他测试
        AppConstant.Storage.apply(AppConstant.DEFAULT_CODE_OUTPUT_ROOT_DIR,
                AppConstant.DEFAULT_CODE_DEPLOY_ROOT_DIR, AppConstant.DEFAULT_CODE_DEPLOY_HOST);
        deleteQuietly(tempDir);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /**
     * 等埋点上报完成并返回那条指标。Vue 分支的埋点在虚拟线程里，测试线程拿不到。
     */
    private GenerationMetric awaitMetric() {
        waitUntil(() -> !captured.isEmpty());
        assertThat(captured).hasSize(1);
        return captured.get(0);
    }

    /** 轮询等待条件成立，最多 5 秒，超时直接断言失败 */
    private void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("等待超时（5s）：条件始终未成立");
    }

    /**
     * 一个能被 HTML 解析器与保存器正常处理的生成结果
     */
    private static String validHtmlStream() {
        return "<html><body>hello</body></html>";
    }

    @Nested
    @DisplayName("HTML / 多文件类型")
    class SimpleTypes {

        @Test
        @DisplayName("保存成功时应上报通过，且一次通过标记为 true")
        void shouldRecordPassedWhenSaveSucceeds() {
            when(aiService.generateHtmlCodeStream(eq(APP_ID), anyString(), anyString()))
                    .thenReturn(Flux.just(validHtmlStream()));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID).blockLast();

            GenerationMetric metric = awaitMetric();
            assertThat(metric.getAppId()).isEqualTo(APP_ID);
            assertThat(metric.getCodeGenType()).isEqualTo(CodeGenTypeEnum.HTML.getValue());
            assertThat(metric.getFirstBuildPassed()).isTrue();
            assertThat(metric.getFinalPassed()).isTrue();
            assertThat(metric.getHealingAttempts()).isZero();
            assertThat(metric.getFailureType()).isNull();
            // 没有构建阶段，构建耗时应为 0，生成耗时为正
            assertThat(metric.getBuildCostMs()).isZero();
            assertThat(metric.getGenerateCostMs()).isGreaterThanOrEqualTo(0L);
            assertThat(metric.getTotalCostMs()).isEqualTo(metric.getGenerateCostMs());
        }

        @Test
        @DisplayName("应统计出产物文件数与字节数，用于衡量生成代码量")
        void shouldMeasureOutputSize() {
            when(aiService.generateHtmlCodeStream(eq(APP_ID), anyString(), anyString()))
                    .thenReturn(Flux.just(validHtmlStream()));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID).blockLast();

            GenerationMetric metric = captured.get(0);
            assertThat(metric.getFileCount()).isEqualTo(1);
            assertThat(metric.getTotalBytes())
                    .isEqualTo(validHtmlStream().getBytes(StandardCharsets.UTF_8).length);
        }

        @Test
        @DisplayName("保存失败时应上报失败，且一次通过标记为 false")
        void shouldRecordFailureWhenSaveFails() {
            // 空白代码会让保存器抛 BusinessException，模拟解析/保存失败
            when(aiService.generateHtmlCodeStream(eq(APP_ID), anyString(), anyString()))
                    .thenReturn(Flux.just("   "));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID).blockLast();

            GenerationMetric metric = awaitMetric();
            assertThat(metric.getFirstBuildPassed()).isFalse();
            assertThat(metric.getFinalPassed()).isFalse();
            assertThat(metric.getFailureType()).isNotNull();
        }

        @Test
        @DisplayName("保存失败也不应把异常抛给调用方")
        void shouldNotPropagateSaveFailure() {
            when(aiService.generateHtmlCodeStream(eq(APP_ID), anyString(), anyString()))
                    .thenReturn(Flux.just(""));

            // blockLast 不抛异常，说明保存失败被内部消化，用户仍能拿到流
            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID).blockLast();

            assertThat(captured).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Vue 工程类型")
    class VueProjectType {

        @Test
        @DisplayName("首次构建即通过时，应上报 firstPass 且自愈轮数为 0")
        void shouldRecordFirstBuildPass() {
            stubHealing(HealingOutcome.of(BuildResult.ok("ok"), true, 0, 1234L));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                    .blockLast();

            awaitMetric();

            GenerationMetric metric = awaitMetric();
            assertThat(metric.getCodeGenType()).isEqualTo(CodeGenTypeEnum.VUE_PROJECT.getValue());
            assertThat(metric.getFirstBuildPassed()).isTrue();
            assertThat(metric.getFinalPassed()).isTrue();
            assertThat(metric.getHealingAttempts()).isZero();
            assertThat(metric.getBuildCostMs()).isEqualTo(1234L);
            assertThat(metric.getTotalCostMs())
                    .isEqualTo(metric.getGenerateCostMs() + 1234L);
        }

        @Test
        @DisplayName("自愈后成功时，应如实记下 firstPass=false 与修复轮数")
        void shouldRecordHealingAttempts() {
            stubHealing(HealingOutcome.of(BuildResult.ok("ok"), false, 2, 8000L));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                    .blockLast();

            awaitMetric();

            GenerationMetric metric = awaitMetric();
            // 首次没过、修 2 轮后过，即自愈生效的证据
            assertThat(metric.getFirstBuildPassed()).isFalse();
            assertThat(metric.getFinalPassed()).isTrue();
            assertThat(metric.getHealingAttempts()).isEqualTo(2);
            assertThat(metric.getFailureType()).isNull();
        }

        @Test
        @DisplayName("自愈用尽仍失败时，应记下失败类型与原因")
        void shouldRecordFailureTypeAndMessage() {
            BuildResult failed = BuildResult.fail(BuildFailureTypeEnum.COMPILE_ERROR, "项目构建",
                    "error: Cannot find module './Missing.vue'", "raw");
            stubHealing(HealingOutcome.of(failed, false, 2, 9000L));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                    .blockLast();

            awaitMetric();

            GenerationMetric metric = awaitMetric();
            assertThat(metric.getFinalPassed()).isFalse();
            assertThat(metric.getFailureType()).isEqualTo(BuildFailureTypeEnum.COMPILE_ERROR.name());
            assertThat(metric.getFailureMessage()).contains("Missing.vue");
            // 失败样本库可能不完整，统计产物规模没有意义
            assertThat(metric.getFileCount()).isNull();
        }

        @Test
        @DisplayName("自愈流程抛异常时，也应留下一条 EXECUTION_ERROR 记录")
        void shouldRecordUnexpectedException() {
            when(healingService.buildWithHealingOutcome(anyString(), anyLong()))
                    .thenThrow(new IllegalStateException("虚拟线程被中断"));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                    .blockLast();

            awaitMetric();

            GenerationMetric metric = awaitMetric();
            assertThat(metric.getFinalPassed()).isFalse();
            assertThat(metric.getFailureType()).isEqualTo(BuildFailureTypeEnum.EXECUTION_ERROR.name());
            assertThat(metric.getFailureMessage()).contains("虚拟线程被中断");
        }

        @Test
        @DisplayName("构建过程不应阻塞代码流返回")
        void shouldNotBlockCodeStream() {
            // 让构建"永远不结束"，若实现是同步的，这个测试就会超时
            when(healingService.buildWithHealingOutcome(anyString(), anyLong()))
                    .thenAnswer(inv -> {
                        Thread.sleep(30_000);
                        return HealingOutcome.of(BuildResult.ok("ok"), true, 0, 1L);
                    });

            long startedAt = System.currentTimeMillis();
            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.VUE_PROJECT, APP_ID).blockLast();
            long cost = System.currentTimeMillis() - startedAt;

            assertThat(cost).isLessThan(3_000L);
            assertThat(captured).isEmpty();
        }
    }

    @Nested
    @DisplayName("旁路属性")
    class BypassSemantics {

        @Test
        @DisplayName("上报失败不应影响代码保存与用户拿到的结果")
        void shouldNotBreakFlowWhenRecorderThrows() {
            // 模拟采集器连 catch 都没兜住的极端情况，验证调用方也没把埋点当必经路径
            org.mockito.Mockito.doThrow(new RuntimeException("统计表不存在"))
                    .when(recorder).record(any(GenerationMetric.class));

            when(aiService.generateHtmlCodeStream(eq(APP_ID), anyString(), anyString()))
                    .thenReturn(Flux.just(validHtmlStream()));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID).blockLast();
        }

        @Test
        @DisplayName("非 Vue 类型不应触发构建自愈")
        void shouldNotBuildForSimpleTypes() {
            when(aiService.generateHtmlCodeStream(eq(APP_ID), anyString(), anyString()))
                    .thenReturn(Flux.just(validHtmlStream()));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID).blockLast();

            verify(healingService, never()).buildWithHealingOutcome(anyString(), anyLong());
            verify(healingService, never()).buildWithHealing(anyString(), anyLong());
        }

        @Test
        @DisplayName("Vue 工程应走自愈构建而非裸构建")
        void shouldUseHealingForVueProject() {
            stubHealing(HealingOutcome.of(BuildResult.ok("ok"), true, 0, 1L));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                    .blockLast();

            waitUntil(() -> org.mockito.Mockito.mockingDetails(healingService)
                    .getInvocations().stream()
                    .anyMatch(i -> "buildWithHealingOutcome".equals(i.getMethod().getName())));
        }
    }

    @Nested
    @DisplayName("评测跑批期间必须让路")
    class EvalScopeYields {

        @Test
        @DisplayName("Vue 类型不应再异步构建一次，避免与评测的同步构建并发抢目录")
        void shouldSkipAsyncBuildDuringEval() {
            EvalScope.runInScope(() ->
                    facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                            .blockLast());

            // 给可能被误触发的虚拟线程一点时间；若真触发了，下面的断言会失败
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            verify(healingService, never()).buildWithHealingOutcome(anyString(), anyLong());
        }

        @Test
        @DisplayName("HTML 类型仍应保存代码，但不再重复上报指标")
        void shouldSaveButNotRecordDuringEval() {
            when(aiService.generateHtmlCodeStream(eq(APP_ID), anyString(), anyString()))
                    .thenReturn(Flux.just(validHtmlStream()));

            EvalScope.runInScope(() ->
                    facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID).blockLast());

            // 代码确实落盘了，评测要靠它判断「保存成功」
            assertThat(Files.exists(outputRoot.resolve("html_" + APP_ID).resolve("index.html")))
                    .as("评测期间保存不能被跳过，否则评测永远判 MISSING_DIST")
                    .isTrue();
            // 但不重复上报：EvalRunner 会带着 batchId 统一落库
            assertThat(captured)
                    .as("重复上报会让同一用例在评测表里出现两条，聚合口径被污染")
                    .isEmpty();
        }

        @Test
        @DisplayName("非评测场景行为不变：照常上报")
        void shouldStillRecordOutsideEval() {
            when(aiService.generateHtmlCodeStream(eq(APP_ID), anyString(), anyString()))
                    .thenReturn(Flux.just(validHtmlStream()));

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID).blockLast();

            assertThat(captured).hasSize(1);
        }

        @Test
        @DisplayName("流在别的线程上完成时也必须让路 —— 判据必须在调用时捕获，回调里读 ThreadLocal 会拿到 false")
        void shouldSkipAsyncBuildWhenStreamCompletesOnAnotherThread() {
            // 上面那条用同步流，onComplete 还在调用线程上；生产环境的 onComplete 由 HTTP 客户端线程发出
            // 用 publishOn 复现：若判断写成「回调里现问 EvalScope.isActive()」，本用例会红而上一条仍绿
            when(aiService.generateVueProjectCodeStream(anyLong(), anyString(), anyString()))
                    .thenReturn(Flux.just("ignored")
                            .publishOn(reactor.core.scheduler.Schedulers.parallel()));

            EvalScope.runInScope(() ->
                    facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                            .blockLast());

            // 给可能被误触发的虚拟线程一点时间；若真触发了，下面的断言会失败
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            verify(healingService, never()).buildWithHealingOutcome(anyString(), anyLong());
        }

        @Test
        @DisplayName("HTML 流在别的线程上完成时也不该重复上报")
        void shouldNotRecordWhenHtmlStreamCompletesOnAnotherThread() {
            when(aiService.generateHtmlCodeStream(eq(APP_ID), anyString(), anyString()))
                    .thenReturn(Flux.just(validHtmlStream())
                            .publishOn(reactor.core.scheduler.Schedulers.parallel()));

            EvalScope.runInScope(() ->
                    facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.HTML, APP_ID).blockLast());

            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertThat(captured)
                    .as("回调线程读不到 EvalScope 时会上报第二条记录，污染聚合口径")
                    .isEmpty();
        }
    }

    /**
     * 让自愈服务返回指定结果
     */
    private void stubHealing(HealingOutcome outcome) {
        when(healingService.buildWithHealingOutcome(anyString(), anyLong())).thenReturn(outcome);
    }

    @Nested
    @DisplayName("项目路径拼装")
    class ProjectPath {

        @Test
        @DisplayName("应把项目路径拼成 code_output/vue_project_<appId>")
        void shouldBuildProjectPathFromConfig() {
            stubHealing(HealingOutcome.of(BuildResult.ok("ok"), true, 0, 1L));
            ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);

            facade.generateCodeStream(USER_MESSAGE, CodeGenTypeEnum.VUE_PROJECT, APP_ID)
                    .blockLast();

            waitUntil(() -> org.mockito.Mockito.mockingDetails(healingService)
                    .getInvocations().stream()
                    .anyMatch(i -> "buildWithHealingOutcome".equals(i.getMethod().getName())));

            verify(healingService).buildWithHealingOutcome(pathCaptor.capture(), eq(APP_ID));
            assertThat(pathCaptor.getValue())
                    .isEqualTo(outputRoot.toString() + File.separator
                            + CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + APP_ID);
        }
    }

    /**
     * 采集器自身的容错语义：与 Facade 无关，放在一起便于阅读。
     */
    @Nested
    @DisplayName("采集器容错")
    class RecorderResilience {

        @Test
        @DisplayName("写库异常应被吞掉，record 永不抛异常")
        void shouldSwallowInsertFailure() {
            CodeGenEvalRecorder realRecorder = new CodeGenEvalRecorder();
            com.quan.aicodehelper.mapper.CodeGenEvalRecordMapper failingMapper =
                    org.mockito.Mockito.mock(com.quan.aicodehelper.mapper.CodeGenEvalRecordMapper.class);
            when(failingMapper.insert(any(com.quan.aicodehelper.model.entity.CodeGenEvalRecord.class)))
                    .thenThrow(new RuntimeException("表不存在"));
            ReflectionTestUtils.setField(realRecorder, "evalRecordMapper", failingMapper);

            realRecorder.record(GenerationMetric.builder().appId(APP_ID).build());
        }

        @Test
        @DisplayName("metric 为 null 时应直接返回")
        void shouldIgnoreNullMetric() {
            CodeGenEvalRecorder realRecorder = new CodeGenEvalRecorder();
            com.quan.aicodehelper.mapper.CodeGenEvalRecordMapper mapper =
                    org.mockito.Mockito.mock(com.quan.aicodehelper.mapper.CodeGenEvalRecordMapper.class);
            ReflectionTestUtils.setField(realRecorder, "evalRecordMapper", mapper);

            realRecorder.record(null);

            verify(mapper, never()).insert(any(com.quan.aicodehelper.model.entity.CodeGenEvalRecord.class));
        }

        @Test
        @DisplayName("目录不存在时统计产物应返回 0 而不是抛异常")
        void shouldReturnZeroForMissingDir() {
            long[] size = CodeGenEvalRecorder.measureOutput("/not/exist/dir");

            assertThat(size).containsExactly(0L, 0L);
        }

        @Test
        @DisplayName("不应把 node_modules 与 dist 算作 AI 生成的代码")
        void shouldExcludeNodeModulesFromOutputSize() throws Exception {
            Path project = Files.createTempDirectory("output-measure");
            try {
                Files.writeString(project.resolve("package.json"), "{\"name\":\"x\"}");
                Path nodeModules = Files.createDirectories(project.resolve("node_modules/lodash"));
                Files.writeString(nodeModules.resolve("index.js"), "// 依赖代码，不是 AI 生成的");
                Path dist = Files.createDirectories(project.resolve("dist"));
                Files.writeString(dist.resolve("index.js"), "// 构建产物，也不是 AI 生成的");
                Path src = Files.createDirectories(project.resolve("src"));
                Files.writeString(src.resolve("main.js"), "// AI 生成的代码");

                long[] size = CodeGenEvalRecorder.measureOutput(project.toString());

                // 只有 package.json 与 src/main.js 计入
                assertThat(size[0]).isEqualTo(2);
            } finally {
                deleteQuietly(project);
            }
        }
    }

}
