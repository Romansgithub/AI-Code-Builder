package com.quan.aicodehelper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 构建相关配置测试。{@code npm install} 用默认源时国内网络拉不动，症状是 install 卡满
 * 300 秒被强杀、{@code node_modules} 一个包都没落地，而生成阶段正常，于是 vue 用例被
 * 误读成「代码生成质量差」。默认值必须随代码走，否则换台机器克隆后为空、故障静默复发。
 */
@DisplayName("Vue 构建配置")
class BuilderPropertiesTest {

    @Nested
    @DisplayName("默认依赖源")
    class DefaultRegistry {

        @Test
        @DisplayName("未配置时默认值应非空 —— 否则会静默退回官方源、构建卡满超时")
        void defaultIsPresent() {
            assertThat(new BuilderProperties().getNpmRegistry()).isNotBlank();
        }

        @Test
        @DisplayName("默认值不应含空白字符 —— 它要拼进命令行，含空格会被切碎成两个参数")
        void defaultHasNoWhitespace() {
            String registry = new BuilderProperties().getNpmRegistry();
            assertThat(registry).doesNotContain(" ").doesNotContain("\t");
        }

        @Test
        @DisplayName("默认值应是可用的 http(s) 地址")
        void defaultIsHttpUrl() {
            assertThat(new BuilderProperties().getNpmRegistry()).startsWith("http");
        }
    }

    @Nested
    @DisplayName("显式留空")
    class BlankMeansNoIntervention {

        @Test
        @DisplayName("显式置空应被原样保留，用来表达「不干预、沿用 npm 自身配置」")
        void blankIsKept() {
            BuilderProperties p = new BuilderProperties();
            p.setNpmRegistry("");
            assertThat(p.getNpmRegistry()).isEmpty();
        }

        @Test
        @DisplayName("置 null 也允许，表示不追加任何参数")
        void nullIsAllowed() {
            BuilderProperties p = new BuilderProperties();
            p.setNpmRegistry(null);
            assertThat(p.getNpmRegistry()).isNull();
        }
    }
}
