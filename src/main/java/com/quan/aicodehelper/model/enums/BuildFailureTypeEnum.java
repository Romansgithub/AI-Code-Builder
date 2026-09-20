package com.quan.aicodehelper.model.enums;

import lombok.Getter;

/**
 * 构建失败类型。
 *
 * <p>区分「代码问题」与「环境问题」是自愈循环的前提：
 * 前者可以靠把报错喂回模型修好，后者重试无意义。</p>
 */
@Getter
public enum BuildFailureTypeEnum {

    /**
     * 未失败。
     */
    NONE(false, "无"),

    /**
     * 项目目录或 package.json 缺失 —— 说明生成阶段本身没产出有效工程。
     */
    INVALID_PROJECT(false, "项目结构不完整"),

    /**
     * npm install 失败 —— 网络、镜像源、依赖版本冲突等环境问题，重试通常无用。
     */
    DEPENDENCY_INSTALL(false, "依赖安装失败"),

    /**
     * npm run build 失败 —— 语法错误、类型错误、缺失引用等，属代码问题，可自愈。
     */
    COMPILE_ERROR(true, "代码编译失败"),

    /**
     * 构建超时 —— 可能是依赖下载慢，也可能是代码导致死循环，保守起见不自动重试。
     */
    TIMEOUT(false, "构建超时"),

    /**
     * 命令执行本身异常（进程启动失败等）。
     */
    EXECUTION_ERROR(false, "命令执行异常"),

    /**
     * 构建成功但未产出 dist 目录。
     */
    MISSING_DIST(false, "构建产物缺失");

    private final boolean healable;
    private final String description;

    BuildFailureTypeEnum(boolean healable, String description) {
        this.healable = healable;
        this.description = description;
    }
}
