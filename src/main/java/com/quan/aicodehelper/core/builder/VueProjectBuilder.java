package com.quan.aicodehelper.core.builder;

import cn.hutool.core.util.RuntimeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VueProjectBuilder {
    /**
     * 执行命令
     * @param workingDir
     * @param command
     * @param timeoutSeconds
     * @return
     */
    private boolean executeCommand(File workingDir, String command, int timeoutSeconds)
    {
        try{
            log.info("在目录{}中执行命令：{}", workingDir.getAbsolutePath(), command);
            Process process = RuntimeUtil.exec(null, workingDir, command.split("\\s+"));

            Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "utf-8"))) {
                    String line;
                    while((line = reader.readLine()) != null)
                    {
                        log.info("[{}]{}",command, line);
                    }
                } catch (IOException ignored){

                }
            });

            Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), "utf-8"))) {
                    String line;
                    while((line = reader.readLine()) != null)
                    {
                        log.warn("[{}]{}",command, line);
                    }
                } catch (IOException ignored){

                }
            });
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished){
                log.error("命令执行超时（{}），强制终止进程", timeoutSeconds);
                process.destroyForcibly();
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0)
            {
                log.info("命令执行成功：{}", command);
                return true;
            } else {
                log.error("命令执行失败，退出码:{}", exitCode);
                return false;
            }
        } catch (Exception e){
            log.error("执行命令失败：{}，错误信息：{}", command, e.getMessage());
            return false;
        }
    }

    /**
     * 执行 npm install
     * @param projectDir
     * @return
     */
    private boolean executeNpmInstall(File projectDir)
    {
        log.info("执行 npm install...");
        String command = String.format("%s install", buildCommand("npm"));
        return executeCommand(projectDir, command, 300);
    }

    /**
     * 执行 npm build
     * @param projectDir
     * @return
     */
    private boolean executeNpmBuild(File projectDir)
    {
        log.info("执行 npm build...");
        String command = String.format("%s run build", buildCommand("npm"));
        return executeCommand(projectDir, command, 180);
    }

    /**
     * 判断是否是Windows系统
     * @return
     */
    private boolean isWindows(){
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 构建命令
     * @param baseCommand
     * @return
     */
    private String buildCommand(String baseCommand){
        if (isWindows()) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }

    /**
     * 构建Vue项目
     * @param projectPath
     * @return
     */
    public boolean buildProject(String projectPath)
    {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()){
            log.error("项目目录不存在：{}", projectPath);
            return false;
        }
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            log.error("package.json 文件不存在：{}", packageJson.getAbsolutePath());
            return false;
        }
        log.info("开始构建Vue项目：{}", projectPath);
        if (!executeNpmInstall(projectDir)){
            log.error("npm install 执行失败，请检查项目依赖");
            return false;
        }
        if (!executeNpmBuild(projectDir))
        {
            log.error("npm build 执行失败，请检查项目构建");
            return false;
        }
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists())
        {
            log.error("dist 目录不存在：{}", distDir.getAbsolutePath());
            return false;
        }
        log.info("Vue 项目构建成功，dist目录:{}", distDir.getAbsolutePath());
        return true;
    }

    /**
     * 异步构建Vue项目
     * @param projectPath
     */
    public void buildProjectAsync(String projectPath) {
        Thread.ofVirtual().name("vue-buider-" + System.currentTimeMillis()).start(() -> {
            try{
                buildProject(projectPath);
            } catch (Exception e)
            {
                log.error("构建Vue项目失败：{}", e.getMessage(), e);
            }
        });
    }

}
