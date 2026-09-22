package com.quan.aicodehelper.core.builder;

import cn.hutool.core.util.RuntimeUtil;
import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.config.BuilderProperties;
import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Vue 项目构建器。
 *
 * <p>相比早期实现的三点关键改进：</p>
 * <ol>
 *   <li><b>收集输出</b>：原实现把子进程输出直接打到日志，调用方拿不到报错文本，无法据此修复。
 *       现在统一收集进 {@link BuildResult}。</li>
 *   <li><b>区分失败类型</b>：依赖装不上（环境问题）与代码编译失败（代码问题）必须分开，
 *       前者重试无意义。见 {@link BuildFailureTypeEnum}。</li>
 *   <li><b>依赖复用</b>：{@code package.json} 未变且 {@code node_modules} 已存在时跳过
 *       {@code npm install}。原实现每次构建都重装依赖，一次最长等 300 秒，
 *       自愈循环若照此重试会让用户等到天荒地老。</li>
 * </ol>
 */
@Slf4j
@Component
public class VueProjectBuilder {

    private static final int NPM_INSTALL_TIMEOUT_SECONDS = 300;
    private static final int NPM_BUILD_TIMEOUT_SECONDS = 180;

    @Resource
    private GenerationProgressRegistry generationProgressRegistry;

    @Resource
    private BuilderProperties builderProperties;

    /**
     * 执行命令并收集输出。
     *
     * @param workingDir     工作目录
     * @param command        命令
     * @param timeoutSeconds 超时时间
     * @param outputHolder   输出收集容器（stdout 与 stderr 合并按序写入）
     * @return 退出码；-1 表示超时或异常
     */
    private int executeCommand(File workingDir, String command, int timeoutSeconds, StringBuilder outputHolder) {
        try {
            log.info("在目录 {} 中执行命令：{}", workingDir.getAbsolutePath(), command);
            Process process = RuntimeUtil.exec(null, workingDir, command.split("\\s+"));

            Thread outReader = Thread.ofVirtual().start(
                    () -> collectStream(process, outputHolder, false));
            Thread errReader = Thread.ofVirtual().start(
                    () -> collectStream(process, outputHolder, true));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{}s），强制终止进程：{}", timeoutSeconds, command);
                process.destroyForcibly();
                appendSafe(outputHolder, "\n[ERROR] 命令执行超时（" + timeoutSeconds + " 秒），进程已被强制终止\n");
                return -1;
            }
            // 等两个读取线程把剩余缓冲刷完，避免输出被截断
            outReader.join(3000);
            errReader.join(3000);

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("命令执行成功：{}", command);
            } else {
                log.error("命令执行失败，退出码 {}：{}", exitCode, command);
            }
            return exitCode;
        } catch (Exception e) {
            log.error("执行命令异常：{}，错误信息：{}", command, e.getMessage());
            appendSafe(outputHolder, "\n[ERROR] 命令执行异常：" + e.getMessage() + "\n");
            return -1;
        }
    }

    /**
     * 读取子进程的一个输出流并写入收集器。
     */
    private void collectStream(Process process, StringBuilder outputHolder, boolean isError) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                isError ? process.getErrorStream() : process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (isError) {
                    log.warn("[build] {}", line);
                } else {
                    log.info("[build] {}", line);
                }
                appendSafe(outputHolder, line + "\n");
            }
        } catch (Exception ignored) {
            // 进程被强制终止时读取会抛异常，属预期情况
        }
    }

    private synchronized void appendSafe(StringBuilder sb, String text) {
        sb.append(text);
    }

    /**
     * 判断是否需要重新安装依赖。
     *
     * <p>依赖是否过期由「{@code package.json} 的修改时间晚于 {@code node_modules} 目录」判定。
     * 这是个近似判断，但足以覆盖「同一项目反复构建」这一主要场景。</p>
     */
    private boolean shouldInstallDependencies(File projectDir) {
        File nodeModules = new File(projectDir, "node_modules");
        if (!nodeModules.exists() || !nodeModules.isDirectory()) {
            return true;
        }
        File packageJson = new File(projectDir, "package.json");
        File packageLock = new File(projectDir, "package-lock.json");
        long nodeModulesTime = nodeModules.lastModified();
        if (packageJson.exists() && packageJson.lastModified() > nodeModulesTime) {
            log.info("package.json 有更新，需要重新安装依赖");
            return true;
        }
        if (packageLock.exists() && packageLock.lastModified() > nodeModulesTime) {
            log.info("package-lock.json 有更新，需要重新安装依赖");
            return true;
        }
        log.info("依赖未变化，跳过 npm install");
        return false;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 拼出可执行命令名（Windows 下需要 .cmd 后缀）。
     */
    private String resolveExecutable(String baseCommand) {
        if (isWindows()) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }

    /**
     * 拼出 npm 依赖源参数（形如 {@code  --registry=https://...}），供 {@code npm install} 使用。
     *
     * <p><b>只在安装了依赖时用得上</b>：{@code npm run build} 不下载包，故不追加，
     * 免得把一个与本步无关的参数混进命令里。</p>
     *
     * <p>配置留空时返回空串，命令与原实现逐字一致 —— 不配镜像就不改变任何行为。</p>
     */
    private String npmRegistryArg() {
        String registry = builderProperties == null ? null : builderProperties.getNpmRegistry();
        if (registry == null || registry.isBlank()) {
            return "";
        }
        return " --registry=" + registry;
    }

    // ==================================================================
    // 对外接口
    // ==================================================================

    /**
     * 构建 Vue 项目，返回结构化结果。
     *
     * @param projectPath 项目目录
     * @return 构建结果（含失败类型与错误信息）
     */
    public BuildResult buildProjectWithResult(String projectPath) {
        return buildProjectWithResult(projectPath, null);
    }

    /**
     * 构建 Vue 项目，返回结构化结果，并把关键进度推送给前端。
     *
     * @param projectPath 项目目录
     * @param appId       应用 ID，用于推送进度；可为 null
     * @return 构建结果
     */
    public BuildResult buildProjectWithResult(String projectPath, Long appId) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在：{}", projectPath);
            return BuildResult.fail(BuildFailureTypeEnum.INVALID_PROJECT, "项目校验",
                    "项目目录不存在：" + projectPath, "");
        }
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            log.error("package.json 文件不存在：{}", packageJson.getAbsolutePath());
            return BuildResult.fail(BuildFailureTypeEnum.INVALID_PROJECT, "项目校验",
                    "package.json 不存在，生成的项目结构不完整", "");
        }

        log.info("开始构建 Vue 项目：{}", projectPath);
        StringBuilder output = new StringBuilder();

        // ---- 阶段一：依赖安装 ----
        if (shouldInstallDependencies(projectDir)) {
            emit(appId, "正在安装项目依赖...");
            String installCommand = resolveExecutable("npm") + " install" + npmRegistryArg();
            int installExit = executeCommand(projectDir, installCommand, NPM_INSTALL_TIMEOUT_SECONDS, output);
            if (installExit != 0) {
                String error = BuildErrorExtractor.extract(output.toString());
                if (installExit == -1 && output.indexOf("超时") >= 0) {
                    return BuildResult.fail(BuildFailureTypeEnum.TIMEOUT, "依赖安装",
                            "依赖安装超时", output.toString());
                }
                return BuildResult.fail(BuildFailureTypeEnum.DEPENDENCY_INSTALL, "依赖安装",
                        error, output.toString());
            }
        }

        // ---- 阶段二：构建 ----
        emit(appId, "正在构建项目...");
        String buildCommand = resolveExecutable("npm") + " run build";
        int buildExit = executeCommand(projectDir, buildCommand, NPM_BUILD_TIMEOUT_SECONDS, output);
        if (buildExit != 0) {
            String error = BuildErrorExtractor.extract(output.toString());
            if (buildExit == -1) {
                return BuildResult.fail(BuildFailureTypeEnum.TIMEOUT, "项目构建",
                        "构建超时", output.toString());
            }
            return BuildResult.fail(BuildFailureTypeEnum.COMPILE_ERROR, "项目构建",
                    error, output.toString());
        }

        // ---- 阶段三：校验产物 ----
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists() || !distDir.isDirectory()) {
            log.error("dist 目录不存在：{}", distDir.getAbsolutePath());
            return BuildResult.fail(BuildFailureTypeEnum.MISSING_DIST, "产物校验",
                    "构建命令返回成功，但 dist 目录不存在", output.toString());
        }
        File distIndex = new File(distDir, "index.html");
        if (!distIndex.exists()) {
            log.error("dist/index.html 不存在：{}", distIndex.getAbsolutePath());
            return BuildResult.fail(BuildFailureTypeEnum.MISSING_DIST, "产物校验",
                    "dist 目录存在但缺少 index.html", output.toString());
        }

        log.info("Vue 项目构建成功，dist 目录：{}", distDir.getAbsolutePath());
        return BuildResult.ok(output.toString());
    }

    /**
     * 构建 Vue 项目（兼容旧签名）。
     *
     * @deprecated 改用 {@link #buildProjectWithResult(String)} 以获取失败原因。
     */
    @Deprecated
    public boolean buildProject(String projectPath) {
        return buildProjectWithResult(projectPath).isSuccess();
    }

    /**
     * 异步构建 Vue 项目。
     */
    public void buildProjectAsync(String projectPath) {
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis()).start(() -> {
            try {
                buildProjectWithResult(projectPath);
            } catch (Exception e) {
                log.error("构建 Vue 项目失败：{}", e.getMessage(), e);
            }
        });
    }

    private void emit(Long appId, String message) {
        if (appId == null || generationProgressRegistry == null) {
            return;
        }
        generationProgressRegistry.emit(appId, message);
    }
}
