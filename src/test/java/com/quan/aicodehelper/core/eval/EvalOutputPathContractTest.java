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
 * 评测产物路径契约测试。写盘侧（{@code FileWriteTool} / {@code CodeFileSaverTemplate}）与读盘侧
 * （{@code EvalRunner}）各自算路径，凡涉及两方约定路径的，必须有一条测试同时校验两端。
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
            // 模拟生成侧：按 {type}_{appId} 在当前产物根下落盘，与生产一致
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
            // Vue 模式下模型通过 FileWriteTool 写盘，它拼的是
            // codeOutputRootDir/{VUE_PROJECT}_{appId}，构建目录必须与之完全一致。
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

        @Test
        @DisplayName("Vue 用例：产物统计必须覆盖 AI 生成的源码，不能恒为 0")
        void shouldMeasureVueSourceNotDist() {
            // measureOutput 自身排除 dist，若把 dist 目录传进去，产物会恒为 0
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
                        FileUtil.mkdir(dir);
                        // AI 生成的源码/配置，才是要统计的东西
                        FileUtil.writeString("{\"name\":\"x\"}",
                                dir + File.separator + "package.json", "utf-8");
                        FileUtil.mkdir(dir + File.separator + "src");
                        FileUtil.writeString("<template/>",
                                dir + File.separator + "src" + File.separator + "App.vue", "utf-8");
                        FileUtil.writeString("// entry",
                                dir + File.separator + "src" + File.separator + "main.js", "utf-8");
                        // 构建产物与依赖：都不该计入
                        FileUtil.mkdir(dir + File.separator + "dist" + File.separator + "assets");
                        FileUtil.writeString("<html></html>",
                                dir + File.separator + "dist" + File.separator + "index.html", "utf-8");
                        FileUtil.writeString("// bundle",
                                dir + File.separator + "dist" + File.separator + "assets"
                                        + File.separator + "index.js", "utf-8");
                        return Flux.just("done");
                    });

            com.quan.aicodehelper.core.healing.BuildSelfHealingService healing =
                    mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class);
            when(healing.buildWithHealingOutcome(anyString(), anyLong()))
                    .thenReturn(com.quan.aicodehelper.core.healing.HealingOutcome.of(
                            com.quan.aicodehelper.core.builder.BuildResult.ok("ok"), true, 0, 1L));
            ReflectionTestUtils.setField(runner, "buildSelfHealingService", healing);

            when(loader.load()).thenReturn(singleCaseSet(caseOf("vue-todo", CodeGenTypeEnum.VUE_PROJECT)));

            EvalRunResult.CaseResult cr = runner.run().getCases().get(0);

            assertThat(cr.isFinalPassed()).isTrue();
            assertThat(cr.getFileCount())
                    .as("应统计到 package.json + src/App.vue + src/main.js 三份源码，"
                            + "而不是被节点排除规则清成 0")
                    .isEqualTo(3);
            assertThat(cr.getTotalBytes())
                    .as("字节数也不能是 0")
                    .isPositive();
        }
    }

    @Nested
    @DisplayName("故障注入")
    class FaultInjectionExecution {

        /** 造一个「模型刚生成完」的 Vue 工程，供注入改坏导入路径 */
        private void stubGeneratedVueProject() {
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
                        FileUtil.mkdir(dir + File.separator + "src");
                        FileUtil.writeString(
                                "<script setup>\nimport Hello from '@/components/Hello.vue'\n</script>\n",
                                dir + File.separator + "src" + File.separator + "App.vue", "utf-8");
                        return Flux.just("done");
                    });
        }

        private static EvalCase healingCase() {
            EvalCase c = caseOf("vue-healing-trigger", CodeGenTypeEnum.VUE_PROJECT);
            EvalCase.FaultInjection fi = new EvalCase.FaultInjection();
            fi.setFile("src/App.vue");
            fi.setFind("'@/components/");
            fi.setReplace("'@/components-broken/");
            c.setFaultInjection(fi);
            return c;
        }

        @Test
        @DisplayName("注入生效：传给构建器的目录里，目标文件确实已被改坏")
        void shouldBreakArtifactBeforeBuild() {
            stubGeneratedVueProject();

            java.util.concurrent.atomic.AtomicReference<String> appVueAtBuildTime =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicInteger builtWithHealing = new java.util.concurrent.atomic.AtomicInteger();

            com.quan.aicodehelper.core.healing.BuildSelfHealingService healing =
                    mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class);
            when(healing.buildWithHealingOutcome(anyString(), anyLong()))
                    .thenAnswer(inv -> {
                        String dir = inv.getArgument(0);
                        builtWithHealing.incrementAndGet();
                        appVueAtBuildTime.set(FileUtil.readString(
                                new File(dir, "src" + File.separator + "App.vue"),
                                java.nio.charset.StandardCharsets.UTF_8));
                        // 模拟真实自愈：第一轮失败，重试后修好
                        return com.quan.aicodehelper.core.healing.HealingOutcome.of(
                                com.quan.aicodehelper.core.builder.BuildResult.ok("ok"), false, 1, 1L);
                    });
            ReflectionTestUtils.setField(runner, "buildSelfHealingService", healing);

            when(loader.load()).thenReturn(singleCaseSet(healingCase()));

            EvalRunResult.CaseResult cr = runner.run().getCases().get(0);

            assertThat(builtWithHealing.get()).isEqualTo(1);
            assertThat(appVueAtBuildTime.get())
                    .as("构建时读到的应该已是破坏后的内容 —— 否则首轮不会失败，自愈也就没被验证")
                    .contains("@/components-broken/")
                    .doesNotContain("@/components/");
            assertThat(cr.isFirstBuildPassed())
                    .as("注入的目的就是让首轮必然失败")
                    .isFalse();
            assertThat(cr.getHealingAttempts()).isEqualTo(1);
            assertThat(cr.isFinalPassed()).isTrue();
        }

        @Test
        @DisplayName("注入失败必须显式报错，绝不能静默跳过产出假数据")
        void shouldFailLoudlyWhenFindStringMissing() {
            // find 串找不到时若静默跳过，首轮会直接构建通过，报告显示一条假的「一次通过」
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
                        FileUtil.mkdir(dir + File.separator + "src");
                        FileUtil.writeString("<script setup>// 没有组件导入</script>\n",
                                dir + File.separator + "src" + File.separator + "App.vue", "utf-8");
                        return Flux.just("done");
                    });

            com.quan.aicodehelper.core.healing.BuildSelfHealingService healing =
                    mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class);
            ReflectionTestUtils.setField(runner, "buildSelfHealingService", healing);

            when(loader.load()).thenReturn(singleCaseSet(healingCase()));

            EvalRunResult.CaseResult cr = runner.run().getCases().get(0);

            assertThat(cr.isFinalPassed()).isFalse();
            assertThat(cr.getFailureMessage())
                    .as("必须写明是注入失败，而不是笼统的构建失败")
                    .contains("故障注入失败")
                    .contains("未找到待替换内容");
            assertThat(cr.getFailureType()).isEqualTo("EXECUTION_ERROR");
            // 注入都没成功，就不该再跑去构建
            org.mockito.Mockito.verify(healing, org.mockito.Mockito.never())
                    .buildWithHealingOutcome(anyString(), anyLong());
        }

        @Test
        @DisplayName("目标文件不存在同样要显式报错")
        void shouldFailWhenTargetFileMissing() {
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
                        FileUtil.mkdir(dir);
                        FileUtil.writeString("{}", dir + File.separator + "package.json", "utf-8");
                        return Flux.just("done");
                    });

            ReflectionTestUtils.setField(runner, "buildSelfHealingService",
                    mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class));
            when(loader.load()).thenReturn(singleCaseSet(healingCase()));

            EvalRunResult.CaseResult cr = runner.run().getCases().get(0);

            assertThat(cr.isFinalPassed()).isFalse();
            assertThat(cr.getFailureMessage())
                    .contains("故障注入失败")
                    .contains("目标文件不存在");
        }

        @Test
        @DisplayName("没配注入的用例行为不变：照常构建，不受注入逻辑影响")
        void normalCaseUnaffected() {
            stubGeneratedVueProject();

            com.quan.aicodehelper.core.healing.BuildSelfHealingService healing =
                    mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class);
            when(healing.buildWithHealingOutcome(anyString(), anyLong()))
                    .thenReturn(com.quan.aicodehelper.core.healing.HealingOutcome.of(
                            com.quan.aicodehelper.core.builder.BuildResult.ok("ok"), true, 0, 1L));
            ReflectionTestUtils.setField(runner, "buildSelfHealingService", healing);

            when(loader.load()).thenReturn(singleCaseSet(
                    caseOf("vue-todo", CodeGenTypeEnum.VUE_PROJECT)));

            EvalRunResult.CaseResult cr = runner.run().getCases().get(0);

            assertThat(cr.isFirstBuildPassed()).isTrue();
            assertThat(cr.isFinalPassed()).isTrue();
            assertThat(cr.getHealingAttempts()).isZero();
        }

        @Test
        @DisplayName("file 留空时应自动定位到含目标串的文件 —— 哪怕它不在 App.vue")
        void shouldAutoLocateFileWhenBlank() {
            // 组件 import 可能写在 pages/Home.vue；写死 src/App.vue 会 100% 注入失败
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
                        FileUtil.mkdir(dir + File.separator + "src" + File.separator + "pages");
                        FileUtil.writeString(
                                "<script setup>\nimport { RouterView } from 'vue-router'\n</script>\n",
                                dir + File.separator + "src" + File.separator + "App.vue", "utf-8");
                        FileUtil.writeString(
                                "<script setup>\nimport FilterBar from '@/components/FilterBar.vue'\n"
                                        + "import InventoryList from '@/components/InventoryList.vue'\n</script>\n",
                                dir + File.separator + "src" + File.separator + "pages"
                                        + File.separator + "Home.vue", "utf-8");
                        return Flux.just("done");
                    });

            java.util.concurrent.atomic.AtomicReference<String> homeAtBuildTime =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<String> appAtBuildTime =
                    new java.util.concurrent.atomic.AtomicReference<>();

            com.quan.aicodehelper.core.healing.BuildSelfHealingService healing =
                    mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class);
            when(healing.buildWithHealingOutcome(anyString(), anyLong()))
                    .thenAnswer(inv -> {
                        String dir = inv.getArgument(0);
                        homeAtBuildTime.set(FileUtil.readString(
                                new File(dir, "src" + File.separator + "pages" + File.separator + "Home.vue"),
                                java.nio.charset.StandardCharsets.UTF_8));
                        appAtBuildTime.set(FileUtil.readString(
                                new File(dir, "src" + File.separator + "App.vue"),
                                java.nio.charset.StandardCharsets.UTF_8));
                        return com.quan.aicodehelper.core.healing.HealingOutcome.of(
                                com.quan.aicodehelper.core.builder.BuildResult.ok("ok"), false, 1, 1L);
                    });
            ReflectionTestUtils.setField(runner, "buildSelfHealingService", healing);

            EvalCase c = caseOf("vue-healing-trigger", CodeGenTypeEnum.VUE_PROJECT);
            EvalCase.FaultInjection fi = new EvalCase.FaultInjection();
            // 刻意不设 file
            fi.setFind("'@/components/");
            fi.setReplace("'@/components-broken/");
            c.setFaultInjection(fi);
            when(loader.load()).thenReturn(singleCaseSet(c));

            EvalRunResult.CaseResult cr = runner.run().getCases().get(0);

            assertThat(homeAtBuildTime.get())
                    .as("注入必须落在真正含目标串的那个文件上")
                    .contains("@/components-broken/")
                    .doesNotContain("@/components/");
            assertThat(appAtBuildTime.get())
                    .as("不含目标串的文件不该被动过")
                    .doesNotContain("components-broken");
            assertThat(cr.isFirstBuildPassed()).isFalse();
            assertThat(cr.getHealingAttempts()).isEqualTo(1);
            assertThat(cr.isFinalPassed()).isTrue();
        }

        @Test
        @DisplayName("自动定位失败时报错要列出实际有哪些源文件，省得下一轮再翻磁盘")
        void shouldListFilesWhenAutoLocateFails() {
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
                        FileUtil.mkdir(dir + File.separator + "src");
                        FileUtil.writeString("<script setup>// 什么导入都没有</script>\n",
                                dir + File.separator + "src" + File.separator + "App.vue", "utf-8");
                        return Flux.just("done");
                    });

            ReflectionTestUtils.setField(runner, "buildSelfHealingService",
                    mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class));

            EvalCase c = caseOf("vue-healing-trigger", CodeGenTypeEnum.VUE_PROJECT);
            EvalCase.FaultInjection fi = new EvalCase.FaultInjection();
            fi.setFind("'@/components/");
            fi.setReplace("'@/components-broken/");
            c.setFaultInjection(fi);
            when(loader.load()).thenReturn(singleCaseSet(c));

            EvalRunResult.CaseResult cr = runner.run().getCases().get(0);

            assertThat(cr.isFinalPassed()).isFalse();
            assertThat(cr.getFailureMessage())
                    .contains("故障注入失败")
                    .contains("未在")
                    .as("带上实际文件列表，才能一次跑批就定位到该改什么")
                    .contains("App.vue");
        }

        @Test
        @DisplayName("自动定位不能扫到 node_modules：那是依赖不是模型写的代码")
        void shouldNotInjectIntoNodeModules() {
            when(facade.generateCodeStream(anyString(), any(), anyLong()))
                    .thenAnswer(inv -> {
                        Long appId = inv.getArgument(2);
                        String dir = AppConstant.getCodeOutputRootDir() + File.separator
                                + CodeGenTypeEnum.VUE_PROJECT.getValue() + "_" + appId;
                        FileUtil.mkdir(dir + File.separator + "src");
                        FileUtil.writeString("<script setup>// 模型没写组件导入</script>\n",
                                dir + File.separator + "src" + File.separator + "App.vue", "utf-8");
                        // 依赖里放一个含目标串的文件：扫描若不限定 src 会误改依赖
                        FileUtil.mkdir(dir + File.separator + "node_modules" + File.separator + "dep");
                        FileUtil.writeString("import x from '@/components/Dep.vue'\n",
                                dir + File.separator + "node_modules" + File.separator + "dep"
                                        + File.separator + "index.js", "utf-8");
                        return Flux.just("done");
                    });

            com.quan.aicodehelper.core.healing.BuildSelfHealingService healing =
                    mock(com.quan.aicodehelper.core.healing.BuildSelfHealingService.class);
            ReflectionTestUtils.setField(runner, "buildSelfHealingService", healing);

            EvalCase c = caseOf("vue-healing-trigger", CodeGenTypeEnum.VUE_PROJECT);
            EvalCase.FaultInjection fi = new EvalCase.FaultInjection();
            fi.setFind("'@/components/");
            fi.setReplace("'@/components-broken/");
            c.setFaultInjection(fi);
            when(loader.load()).thenReturn(singleCaseSet(c));

            EvalRunResult.CaseResult cr = runner.run().getCases().get(0);

            assertThat(cr.isFinalPassed()).isFalse();
            assertThat(cr.getFailureMessage())
                    .as("src 下没有目标串就该报错，而不是去改 node_modules 里的依赖")
                    .contains("故障注入失败");
            org.mockito.Mockito.verify(healing, org.mockito.Mockito.never())
                    .buildWithHealingOutcome(anyString(), anyLong());
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

            // 读产物时再清一次目录会把刚落盘的文件删掉、判成 MISSING_DIST，这里锁死
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

            // 把上次的残留产物塞回去再跑一次：若不清目录会被误判为通过
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
