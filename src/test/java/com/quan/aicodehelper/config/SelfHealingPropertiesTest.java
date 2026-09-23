package com.quan.aicodehelper.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 构建自愈配置测试：重点锁死边界值兜底，非法配置不能让自愈循环失控。
 */
@DisplayName("构建自愈配置")
class SelfHealingPropertiesTest {

    @Test
    @DisplayName("默认开启且保留 2 轮重试")
    void defaults() {
        SelfHealingProperties props = new SelfHealingProperties();
        props.init();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getMaxAttempts()).isEqualTo(2);
        assertThat(props.getMaxErrorLength()).isEqualTo(3000);
        assertThat(props.isActive()).isTrue();
    }

    @Test
    @DisplayName("关闭开关后 isActive 应为 false")
    void disabled() {
        SelfHealingProperties props = new SelfHealingProperties();
        props.setEnabled(false);
        props.init();

        assertThat(props.isActive()).isFalse();
    }

    @Test
    @DisplayName("轮次为 0 时不应视为生效")
    void zeroAttempts() {
        SelfHealingProperties props = new SelfHealingProperties();
        props.setMaxAttempts(0);
        props.init();

        assertThat(props.isActive()).isFalse();
    }

    @Test
    @DisplayName("负数轮次应被重置为 0，避免自愈循环失控")
    void negativeAttemptsReset() {
        SelfHealingProperties props = new SelfHealingProperties();
        props.setMaxAttempts(-5);
        props.init();

        assertThat(props.getMaxAttempts()).isZero();
        assertThat(props.isActive()).isFalse();
    }

    @Test
    @DisplayName("非法错误长度应回退到默认值，避免提示词失去长度约束")
    void invalidErrorLengthReset() {
        SelfHealingProperties props = new SelfHealingProperties();
        props.setMaxErrorLength(0);
        props.init();
        assertThat(props.getMaxErrorLength()).isEqualTo(3000);

        SelfHealingProperties props2 = new SelfHealingProperties();
        props2.setMaxErrorLength(-100);
        props2.init();
        assertThat(props2.getMaxErrorLength()).isEqualTo(3000);
    }
}
