package com.quan.aicodehelper.core.builder;

import com.quan.aicodehelper.model.enums.BuildFailureTypeEnum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 构建结果与失败类型测试。
 *
 * <p>核心断言：只有「代码问题」才可自愈。这条规则决定了自愈循环会不会
 * 在环境问题上白白消耗 token 和用户时间。</p>
 */
@DisplayName("构建结果")
class BuildResultTest {

    @Nested
    @DisplayName("成功结果")
    class SuccessCase {

        @Test
        @DisplayName("ok() 应标记成功且不可自愈")
        void okResult() {
            BuildResult r = BuildResult.ok("build output");

            assertThat(r.isSuccess()).isTrue();
            assertThat(r.getFailureType()).isEqualTo(BuildFailureTypeEnum.NONE);
            assertThat(r.isHealable()).isFalse();
        }
    }

    @Nested
    @DisplayName("失败结果")
    class FailureCase {

        @Test
        @DisplayName("代码编译失败必须可自愈 —— 这是自愈循环存在的意义")
        void compileErrorIsHealable() {
            BuildResult r = BuildResult.fail(BuildFailureTypeEnum.COMPILE_ERROR, "项目构建",
                    "error: Cannot find module './Foo.vue'", "raw");

            assertThat(r.isSuccess()).isFalse();
            assertThat(r.isHealable()).isTrue();
            assertThat(r.getStage()).isEqualTo("项目构建");
            assertThat(r.getErrorMessage()).contains("Cannot find module");
        }

        @Test
        @DisplayName("依赖安装失败不可自愈 —— 改代码解决不了网络/版本冲突问题")
        void dependencyInstallNotHealable() {
            BuildResult r = BuildResult.fail(BuildFailureTypeEnum.DEPENDENCY_INSTALL, "依赖安装",
                    "npm ERR! network timeout", "raw");

            assertThat(r.isHealable()).isFalse();
        }

        @Test
        @DisplayName("超时不可自愈 —— 重试只会再等一个超时周期")
        void timeoutNotHealable() {
            BuildResult r = BuildResult.fail(BuildFailureTypeEnum.TIMEOUT, "项目构建", "超时", "raw");

            assertThat(r.isHealable()).isFalse();
        }

        @Test
        @DisplayName("项目结构不完整不可自愈")
        void invalidProjectNotHealable() {
            BuildResult r = BuildResult.fail(BuildFailureTypeEnum.INVALID_PROJECT, "项目校验",
                    "package.json 不存在", "");

            assertThat(r.isHealable()).isFalse();
        }

        @Test
        @DisplayName("缺少 dist 产物不可自愈（构建已成功，问题在配置）")
        void missingDistNotHealable() {
            BuildResult r = BuildResult.fail(BuildFailureTypeEnum.MISSING_DIST, "产物校验",
                    "dist 目录不存在", "raw");

            assertThat(r.isHealable()).isFalse();
        }

        @Test
        @DisplayName("命令执行异常不可自愈")
        void executionErrorNotHealable() {
            BuildResult r = BuildResult.fail(BuildFailureTypeEnum.EXECUTION_ERROR, "命令执行",
                    "进程启动失败", "");

            assertThat(r.isHealable()).isFalse();
        }

        @Test
        @DisplayName("failureType 为 null 时不应误判为可自愈")
        void nullFailureTypeIsNotHealable() {
            BuildResult r = new BuildResult();
            r.setSuccess(false);

            assertThat(r.isHealable()).isFalse();
        }
    }

    @Nested
    @DisplayName("失败类型枚举")
    class FailureTypeEnumCase {

        @Test
        @DisplayName("只有 COMPILE_ERROR 是可自愈的")
        void onlyCompileErrorIsHealable() {
            for (BuildFailureTypeEnum type : BuildFailureTypeEnum.values()) {
                boolean expected = type == BuildFailureTypeEnum.COMPILE_ERROR;
                assertThat(type.isHealable())
                        .as("失败类型 %s 的可自愈标志", type)
                        .isEqualTo(expected);
            }
        }

        @Test
        @DisplayName("每个类型都应有可读描述，用于前端提示")
        void everyTypeHasDescription() {
            for (BuildFailureTypeEnum type : BuildFailureTypeEnum.values()) {
                assertThat(type.getDescription()).isNotBlank();
            }
        }
    }
}
