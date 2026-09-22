package com.quan.aicodehelper.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Vue 项目构建相关配置。
 *
 * <p><b>为什么需要它</b>：Vue 用例构建前要执行 {@code npm install}。npm 默认源是
 * {@code registry.npmjs.org}，国内网络下经常拉不动 —— 典型症状是 install 卡满
 * 300 秒超时上限被强杀，产物目录里 {@code node_modules} 一个包都没落地，
 * 而生成阶段（不依赖网络）完全正常。这种失败会伪装成「代码生成质量差」，
 * 极易误判。2026-09-22 的评测跑批就因此让 4 条 vue 用例全军覆没。</p>
 *
 * <p>这里只提供一个可覆盖的源地址，不擅自改用户的全局 npm 配置：
 * 以命令行参数 {@code --registry=<url>} 传给 {@code npm install}，
 * 作用域仅限该次构建。</p>
 *
 * <p><b>留空即不干预</b>：值为空时不追加任何参数，命令与原实现逐字一致 ——
 * 避免「没配镜像却悄悄改变了命令行为」。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.builder")
public class BuilderProperties {

    /**
     * npm 依赖源地址。留空表示不干预，沿用 npm 自身的 registry 配置。
     *
     * <p><b>为什么默认值写在代码里而不是只写在 application.yml</b>：该文件被
     * {@code .gitignore} 排除（含密钥，绝不入库），仓库里也没有 example 模板。
     * 若默认值只存在于 yml，换台机器克隆下来后此项为空 —— 表现不是报错，
     * 而是悄悄退回官方源、构建卡满 300 秒超时上限，即本次修复的原始故障原地复发。
     * 放在代码里的默认值随仓库走，yml 中存在同名项时仍以 yml 为准。</p>
     */
    private String npmRegistry = "https://registry.npmmirror.com";
}
