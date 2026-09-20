package com.quan.aicodehelper.core.eval;

import cn.hutool.core.io.FileUtil;
import com.quan.aicodehelper.ai.facade.AiCodeGeneratorFacade;
import com.quan.aicodehelper.config.EvalProperties;
import com.quan.aicodehelper.constant.AppConstant;
import com.quan.aicodehelper.model.dto.eval.EvalCase;
import com.quan.aicodehelper.model.dto.eval.EvalSet;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 评测产物路径契约测试。
 *
 * <p><b>为什么必须有这一组</b>：2026-09-20 跑批 8 条用例全判失败，日志里却写着
 * 「Vue 项目构建成功」。原因是「写盘的一方」（{@code FileWriteTool} /
 * {@code CodeFileSaverTemplate}，按 {@code {type}_{appId}} 落盘）和
 * 「读盘的一方」（{@code EvalRunner}，当时自己发明了 {@code eval-run/{type}_{caseId}}）
 * 各自算路径，两条路径没有任何交集。</p>
 *
 * <p>当时 263 个测试全绿也没拦住 —— 因为没有一个测试把「生成侧落盘位置」与
 * 「评测侧读取位置」放在一起断言。这个类就是补这个洞：<b>凡是涉及两方约定路径的，
 * 必须有一条测试同时校验两端。</b></p>
 */
@DisplayName("评测产物路径契约")
class EvalOutputPathContractTest {

    private static final String ORIGINAL_OUTPUT = AppConstant.getCodeOutputRootDir();
    private static final String ORIGINAL_DEPLOY = AppConstant.getCodeDeployRootDir();
    private static final String ORIGINAL_HOST = AppConstant.getCodeDeployHost();

    private EvalRunner runner;
    private EvalProperties props;
    private AiCodeGeneratorFacade facade;
    private EvalSetLoader loader;
    private Path tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("eval-path-contract");
        Path codeOutput = Files.createDirectories(tempRoot.resolve("code_output"));
        AppConstant.Storage.apply(codeOutput.toString(),
                tempRoot.resolve("code_deploy").toString(), "http://localhost:8080/api/static/");

        props = new EvalProperties();
        props.setEnabled(true);
        props.setCaseTimeoutSeconds(30);
        props.setOutputSubDir("eval-run");
        props.init();

        facade = mock(AiCodeGeneratorFacade.class);
        loader = mock(EvalSetLoader.class);

        runner = new EvalRunner();
        ReflectionTestUtils.setField(runner, "evalProperties", props);
        ReflectionTestUtils.setField(runner, "aiCodeGeneratorFacade", facade);
        ReflectionTestUtils.setField(runner, "evalSetLoader", loader);
        ReflectionTestUtils.setField(runner, "buildSelfHealingService",
                mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class));
        ReflectionTestUtils.setField(runner, "codeGenEvalRecorder", mock(CodeGenEvalRecorder.class));
    }

    @AfterEach
    void tearDown() {
        // AppConstant 是全局静态，必须还原，否则会影响同 JVM 内其他测试
        AppConstant.Storage.apply(ORIGINAL_OUTPUT, ORIGINAL_DEPLOY, ORIGINAL_HOST);
        deleteQuietly(tempRoot);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // 清理失败不影响断言
                }
            });
        } catch (Exception ignored) {
        }
    }

    private EvalSet singleCaseSet(EvalCase evalCase) {
        EvalSet set = new EvalSet();
        set.setName("契约测试集");
        set.setVersion("1.0");
        set.setCases(java.util.List.of(evalCase));
        return set;
    }

    private static EvalCase caseOf(String id, CodeGenTypeEnum type) {
        EvalCase c = new EvalCase();
        c.setId(id);
        c.setCodeGenType(type);
        c.setPrompt("p");
        return c;
    }

    @Nested
    @DisplayName("生成侧落盘位置 == 评测侧读取位置")
    class WriteReadAgreement {

        @Test
        @DisplayName("HTML 用例：按生产命名 {type}_{appId} 落在 eval-run 下的文件，应被判为通过")
        void shouldFindHtmlArtifactByProductionNaming() {
            // 模拟生成侧行为：评测跑批期间，保存器会按 {type}_{appId} 落盘。
            // 这里在 generateCodeStream 被调用时，用「调用方传入的 appId」在
            // 当前产物根下造出与生产完全一致的文件。
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.HTML.getValue() + "_" + appId;
                        FileUtil.mkdir(dir);
                        FileUtil.writeString("<html>ok</html>",
                                dir + File.separator + "index.html", "utf-8");
                        return Flux.just("done");
                    });

            when(loader.load()).thenReturn(singleCaseSet(caseOf("html-todo", CodeGenTypeEnum.HTML)));

            EvalRunResult result = runner.run();

            EvalRunResult.CaseResult cr = result.getCases().get(0);
            assertThat(cr.isFinalPassed())
                    .as("生成侧按 {type}_{appId} 落盘，评测侧必须能找到 —— 找不到就是路径契约又漂移了")
                    .isTrue();
            assertThat(cr.getFileCount()).isEqualTo(1);
            assertThat(cr.getFailureType()).isNull();
        }

        @Test
        @DisplayName("MULTI_FILE 用例：多文件产物同样应被找到")
        void shouldFindMultiFileArtifact() {
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.MULTI_FILE.getValue() + "_" + appId;
                        FileUtil.mkdir(dir);
                        FileUtil.writeString("<html></html>", dir + File.separator + "index.html", "utf-8");
                        FileUtil.writeString("body{}", dir + File.separator + "style.css", "utf-8");
                        FileUtil.writeString("// js", dir + File.separator + "script.js", "utf-8");
                        return Flux.just("done");
                    });

            when(loader.load()).thenReturn(singleCaseSet(caseOf("multi-file-blog", CodeGenTypeEnum.MULTI_FILE)));

            EvalRunResult result = runner.run();

            EvalRunResult.CaseResult cr = result.getCases().get(0);
            assertThat(cr.isFinalPassed()).isTrue();
            assertThat(cr.getFileCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("Vue 用例：构建器收到的目录，就是模型写盘工具会写入的目录")
        void shouldBuildVueFromSameDirModelWritesTo() {
            // 这条是本次事故的核心：Vue 模式下模型通过 FileWriteTool 写盘，
            // 而 FileWriteTool 拼的是 codeOutputRootDir/{VUE_PROJECT}_{appId}。
            // 因此评测传给构建器的目录必须与之完全一致。
            java.util.concurrent.atomic.AtomicReference<String> modelWriteDir =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> buildDir =
                    new java.util.concurrent.atomic.AtomicReference<>();

            // 模拟生成侧：按 FileWriteTool 的命名规则落盘（含 package.json 与 dist）
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
                        modelWriteDir.set(dir);
                        FileUtil.mkdir(dir);
                        FileUtil.writeString("{\"name\":\"x\"}",
                                dir + File.separator + "package.json", "utf-8");
                        FileUtil.mkdir(dir + File.separator + "dist");
                        FileUtil.writeString("<html></html>",
                                dir + File.separator + "dist" + File.separator + "index.html", "utf-8");
                        return Flux.just("done");
                    });

            // 模拟构建器：记录它被要求构建哪个目录
            com.quan.aicodehelper.core.healing.BuildSelfHealingService healing =
                    mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class);
            when(healing.buildWithHealingOutcome(anyString(), anyLong()))
                    .thenAnswer(inv -> {
                        buildDir.set(inv.getArgument(0));
                        return com.quan.aicodehelper.core.healing.HealingOutcome.of(
                                com.quan.aicodehelper.core.builder.BuildResult.ok("ok"), true, 0, 1L);
                    });
            ReflectionTestUtils.setField(runner, "buildSelfHealingService", healing);

            when(loader.load()).thenReturn(singleCaseSet(caseOf("vue-todo", CodeGenTypeEnum.VUE_PROJECT)));

            runner.run();

            assertThat(buildDir.get())
                    .as("模型写盘目录与构建目录必须是同一个 —— 不一致就是 2026-09-20 那次全失败的根因")
                    .isNotNull()
                    .isEqualTo(modelWriteDir.get());
        }
    }

    @Nested
    @DisplayName("目录清理不能吃掉刚落盘的代码")
    class CleanMustNotEatArtifacts {

        @Test
        @DisplayName("读产物这一步不应再做一次目录清空")
        void shouldNotCleanAfterGeneration() {
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.HTML.getValue() + "_" + appId;
                        FileUtil.mkdir(dir);
                        FileUtil.writeString("<html>ok</html>",
                                dir + File.separator + "index.html", "utf-8");
                        return Flux.just("done");
                    });

            when(loader.load()).thenReturn(singleCaseSet(caseOf("html-todo", CodeGenTypeEnum.HTML)));

            EvalRunResult result = runner.run();

            // 早先的实现里，executeSimpleCase 又调了一次带 FileUtil.clean 的路径计算，
            // 结果把刚落盘的文件删掉、再判成 MISSING_DIST —— 这里锁死不再发生。
            assertThat(result.getCases().get(0).isFinalPassed()).isTrue();
            assertThat(result.getCases().get(0).getFailureType()).isNull();
        }

        @Test
        @DisplayName("跨轮次残留应被清掉，避免上轮产物造成假阳性")
        void shouldCleanStaleArtifactsFromPreviousRun() {
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        // 本次生成什么都没写（模拟解析失败）
                        FileUtil.mkdir(AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.HTML.getValue() + "_" + appId);
                        return Flux.just("done");
                    });

            when(loader.load()).thenReturn(singleCaseSet(caseOf("html-todo", CodeGenTypeEnum.HTML)));

            EvalRunResult result = runner.run();

            // 首轮：无产物 → 失败
            assertThat(result.getCases().get(0).isFinalPassed()).isFalse();
            assertThat(result.getCases().get(0).getFailureType()).isEqualTo("MISSING_DIST");

            // 关键：把上次的「残留产物」塞回去再跑一次 —— 若不清目录，会被误判为通过
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.HTML.getValue() + "_" + appId;
                        FileUtil.mkdir(dir);
                        FileUtil.writeString("<html>stale</html>",
                                dir + File.separator + "index.html", "utf-8");
                        return Flux.just("done");
                    });
            EvalRunResult second = runner.run();

            assertThat(second.getCases().get(0).isFinalPassed())
                    .as("第二轮确实写了文件，应通过")
                    .isTrue();
            // 再验证清理行为：第三轮不写任何东西，必须失败（说明前一轮残留被清掉了）
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> Flux.just("done"));
            EvalRunResult third = runner.run();
            assertThat(third.getCases().get(0).isFinalPassed())
                    .as("本轮没写任何文件，若仍判通过说明没清目录、读到了上一轮残留")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("全局产物根必须还原")
    class OutputRootRestoration {

        @Test
        @DisplayName("跑批期间指向 eval-run，跑完应还原为原值")
        void shouldRestoreOriginalOutputRoot() {
            String before = AppConstant.getCodeOutputRootDir();

            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        // 断言「跑批期间」产物根确实被切到了 eval-run 下
                        assertThat(AppConstant.getCodeOutputRootDir())
                                .isEqualTo(before + File.separator + "eval-run");
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.HTML.getValue() + "_" + appId;
                        FileUtil.mkdir(dir);
                        FileUtil.writeString("<html>ok</html>",
                                dir + File.separator + "index.html", "utf-8");
                        return Flux.just("done");
                    });

            when(loader.load()).thenReturn(singleCaseSet(caseOf("html-todo", CodeGenTypeEnum.HTML)));

            runner.run();

            assertThat(AppConstant.getCodeOutputRootDir())
                    .as("不还原会让评测产物污染之后所有真实用户的生成")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("跑批抛异常时也必须还原，不能把标记漏出去")
        void shouldRestoreEvenWhenBatchThrows() {
            String before = AppConstant.getCodeOutputRootDir();
            when(loader.load()).thenThrow(new IllegalStateException("评测集读不出来"));

            try {
                runner.run();
            } catch (Exception ignored) {
                // 这里不关心异常类型，只关心副作用被还原
            }

            assertThat(AppConstant.getCodeOutputRootDir()).isEqualTo(before);
        }
    }
}
