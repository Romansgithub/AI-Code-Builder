package com.quan.aicodehelper.config;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Vue 项目构建相关配置。Vue 用例构建前要跑 npm install；国内网络下默认官方源常拉不动、伪装成代码质量差。
 * 只以命令行 --registry=<url> 传给该次 install（不擅自改用户全局 npm 配置）；留空即不干预、命令与原实现一致。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.builder")
public class BuilderProperties {

    /**
     * npm 依赖源地址，留空表示不干预，沿用 npm 自身的 registry 配置。
     * 默认值也写在代码里而非只写在 application.yml：yml 被 .gitignore 排除，克隆下来为空会悄悄
     * 退回官方源、构建卡满超时；yml 里配了同名项则仍以 yml 为准。
     */
    private String npmRegistry = "https://registry.npmmirror.com";
}
