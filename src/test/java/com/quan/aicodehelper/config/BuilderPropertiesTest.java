package com.quan.aicodehelper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 构建相关配置测试。
 *
 * <p><b>回归背景</b>：{@code npm install} 用默认源 {@code registry.npmjs.org} 时，
 * 国内网络下拉不动 —— 症状是 install 卡满 300 秒超时上限被强杀、产物目录里
 * {@code node_modules} 一个包都没落地，而生成阶段完全正常，于是整轮评测的
 * vue 用例被误读成「代码生成质量差」。2026-09-22 的跑批就因此 4 条 vue 全灭。</p>
 *
 * <p>修复方式是以命令行参数追加 {@code --registry=<镜像>}。而 {@code application.yml}
 * 被 {@code .gitignore} 排除、仓库内也无 example 模板，因此<b>默认值必须随代码走</b>：
 * 若默认值只写在 yml 里，换台机器克隆后该值为空，故障会静默复发（不报错、只是又卡满 300 秒）。
 * 本测试锁死「默认值可用」与「显式留空即不干预」两个行为。</p>
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
