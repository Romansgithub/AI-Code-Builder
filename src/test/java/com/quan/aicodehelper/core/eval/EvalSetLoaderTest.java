package com.quan.aicodehelper.core.eval;

import com.quan.aicodehelper.config.EvalProperties;
import com.quan.aicodehelper.exception.BusinessException;
import com.quan.aicodehelper.model.dto.eval.EvalCase;
import com.quan.aicodehelper.model.dto.eval.EvalSet;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 评测集加载测试。
 *
 * <p>重点是「默认那份评测集真的能加载」+「坏数据必须被拦住」。
 * 前者防止改 JSON 时手滑（比如枚举值写错），后者防止错误数据
 * 悄悄产出看起来合理的错误指标。</p>
 */
@DisplayName("评测集加载器")
class EvalSetLoaderTest {

    private EvalSetLoader loader;

    /**
     * 用临时文件承载 JSON 内容，走真实的「读资源 + 解析 + 校验」路径。
     */
    private EvalSet parseWith(String json) {
        java.nio.file.Path dir;
        java.nio.file.Path file;
        try {
            dir = java.nio.file.Files.createTempDirectory("eval-set-test");
            file = dir.resolve("set.json");
            java.nio.file.Files.writeString(file, json, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("创建临时评测集文件失败", e);
        }
        try {
            EvalSetLoader l = new EvalSetLoader();
            EvalProperties p = new EvalProperties();
            p.setEvalSetPath(file.toString());
            p.init();
            ReflectionTestUtils.setField(l, "evalProperties", p);
            return l.load();
        } finally {
            deleteRecursively(dir);
        }
    }

    /**
     * 递归删除临时目录。清理失败不影响断言，因此异常一律吞掉。
     */
    private static void deleteRecursively(java.nio.file.Path dir) {
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(x -> {
                        try {
                            java.nio.file.Files.deleteIfExists(x);
                        } catch (Exception ignored) {
                            // 测试清理失败无需中断用例
                        }
                    });
        } catch (Exception ignored) {
            // 同上
        }
    }

    @BeforeEach
    void setUp() {
        loader = new EvalSetLoader();
        EvalProperties props = new EvalProperties();
        props.setEvalSetPath("eval/codegen-eval-set.json");
        props.init();
        ReflectionTestUtils.setField(loader, "evalProperties", props);
    }

    @Nested
    @DisplayName("加载内置评测集")
    class LoadBuiltin {

        @Test
        @DisplayName("默认评测集应能成功加载且包含全部三种生成类型")
        void shouldLoadBuiltinSet() {
            EvalSet evalSet = loader.load();

            assertThat(evalSet).isNotNull();
            assertThat(evalSet.getName()).isNotBlank();
            assertThat(evalSet.getVersion()).isNotBlank();
            assertThat(evalSet.getCases()).isNotEmpty();

            List<CodeGenTypeEnum> types = evalSet.getCases().stream()
                    .map(EvalCase::getCodeGenType)
                    .toList();
            // 三种类型都要有用例，否则报告里的分类型指标会有空缺
            assertThat(types).contains(CodeGenTypeEnum.HTML,
                    CodeGenTypeEnum.MULTI_FILE, CodeGenTypeEnum.VUE_PROJECT);
        }

        @Test
        @DisplayName("每条用例都应有 id、描述与非空 prompt")
        void shouldHaveCompleteFields() {
            EvalSet evalSet = loader.load();

            for (EvalCase c : evalSet.getCases()) {
                assertThat(c.getId()).as("用例 id").isNotBlank();
                assertThat(c.getDescription()).as("用例 %s 的描述", c.getId()).isNotBlank();
                assertThat(c.getPrompt()).as("用例 %s 的 prompt", c.getId()).isNotBlank();
                assertThat(c.getCodeGenType()).as("用例 %s 的类型", c.getId()).isNotNull();
            }
        }

        @Test
        @DisplayName("用例 id 应唯一，且可作为目录名安全使用")
        void shouldHaveUniqueSafeIds() {
            EvalSet evalSet = loader.load();

            List<String> ids = evalSet.getCases().stream().map(EvalCase::getId).toList();
            assertThat(ids).doesNotHaveDuplicates();
            // id 会被拼进产物目录，必须不含路径分隔符等危险字符
            for (String id : ids) {
                assertThat(id).matches("^[A-Za-z0-9_-]+$");
            }
        }

        @Test
        @DisplayName("Vue 用例应覆盖构建场景，否则自愈指标永远没数据")
        void shouldIncludeVueCases() {
            EvalSet evalSet = loader.load();

            long vueCount = evalSet.getCases().stream()
                    .filter(c -> c.getCodeGenType() == CodeGenTypeEnum.VUE_PROJECT)
                    .count();
            assertThat(vueCount).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    @DisplayName("异常输入")
    class InvalidInput {

        private EvalSetLoader loaderWithPath(String path) {
            EvalSetLoader l = new EvalSetLoader();
            EvalProperties p = new EvalProperties();
            p.setEvalSetPath(path);
            p.init();
            ReflectionTestUtils.setField(l, "evalProperties", p);
            return l;
        }

        @Test
        @DisplayName("资源文件不存在时应报错，而不是返回空评测集")
        void shouldFailWhenResourceMissing() {
            EvalSetLoader l = loaderWithPath("eval/not-exists.json");

            assertThatThrownBy(l::load)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("读取评测集失败");
        }
    }

    @Nested
    @DisplayName("类型解析")
    class TypeResolution {

        @Test
        @DisplayName("写业务 value（html）应能识别")
        void shouldAcceptValueStyle() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"html","prompt":"p"}]}
                    """;

            EvalSet set = parseWith(json);

            assertThat(set.getCases().get(0).getCodeGenType()).isEqualTo(CodeGenTypeEnum.HTML);
        }

        @Test
        @DisplayName("写常量名（HTML）也应能识别 —— 两种写法都很自然，都得认")
        void shouldAcceptConstantNameStyle() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"HTML","prompt":"p"}]}
                    """;

            EvalSet set = parseWith(json);

            assertThat(set.getCases().get(0).getCodeGenType()).isEqualTo(CodeGenTypeEnum.HTML);
        }

        @Test
        @DisplayName("常量名大小写不敏感")
        void shouldBeCaseInsensitiveForConstantName() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"vue_project","prompt":"p"}]}
                    """;

            EvalSet set = parseWith(json);

            assertThat(set.getCases().get(0).getCodeGenType()).isEqualTo(CodeGenTypeEnum.VUE_PROJECT);
        }

        @Test
        @DisplayName("无法识别的类型应报错，并指明是哪条用例、可选值有哪些")
        void shouldRejectUnknownType() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"bad-case","description":"d","codeGenType":"react_app","prompt":"p"}]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("bad-case")
                    .hasMessageContaining("react_app")
                    // 写错的人需要知道能填什么
                    .hasMessageContaining("HTML");
        }

        @Test
        @DisplayName("类型字段缺失应报错并指出是第几条用例")
        void shouldRejectMissingType() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","prompt":"p"}]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("第 1 条用例")
                    .hasMessageContaining("codeGenType 未填写");
        }
    }

    @Nested
    @DisplayName("故障注入配置")
    class FaultInjectionParsing {

        @Test
        @DisplayName("内置评测集的注入串必须真的出现过的写法 —— 否则注入永远不会命中")
        void builtinInjectionFindShouldMatchRealModelOutput() {
            EvalSet evalSet = loader.load();

            List<EvalCase> withInjection = evalSet.getCases().stream()
                    .filter(c -> c.getFaultInjection() != null)
                    .toList();
            assertThat(withInjection).isNotEmpty();

            for (EvalCase c : withInjection) {
                EvalCase.FaultInjection fi = c.getFaultInjection();
                // 这里钉死「模型写的是别名导入」这个实测事实。历史上 find 串曾写成
                // from './components/（相对路径），而模型实际一律写 @/components/
                // （13/13），命中率 0% —— 注入从未生效，整条自愈链路等于没验证过。
                assertThat(fi.getFind())
                        .as("模型实测一律使用 @ 别名导入；写成相对路径会 100% 不命中")
                        .contains("@/components/");
                assertThat(fi.getReplace())
                        .as("替换目标必须是不存在的目录，否则构建仍然通过、注入失去意义")
                        .isNotEqualTo(fi.getFind())
                        .contains("components-broken");
            }
        }

        @Test
        @DisplayName("内置评测集的注入用例应留空 file —— 模型把 import 写在哪个文件并不固定")
        void builtinInjectionShouldNotPinFile() {
            EvalSet evalSet = loader.load();

            List<EvalCase> withInjection = evalSet.getCases().stream()
                    .filter(c -> c.getFaultInjection() != null)
                    .toList();
            assertThat(withInjection).isNotEmpty();

            for (EvalCase c : withInjection) {
                assertThat(c.getFaultInjection().getFile())
                        .as("历次跑批目标是 App.vue / pages/Home.vue / pages/HomePage.vue 不等，"
                                + "写死任一个都会在模型换一种组织方式时失效")
                        .isNullOrEmpty();
            }
        }

        @Test
        @DisplayName("未写 faultInjection 的用例应解析为 null，走正常流程")
        void shouldBeNullWhenAbsent() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"vue_project","prompt":"p"}]}
                    """;

            EvalSet set = parseWith(json);

            assertThat(set.getCases().get(0).getFaultInjection()).isNull();
        }

        @Test
        @DisplayName("file 留空是合法的，表示自动定位")
        void shouldAllowBlankFile() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"vue_project","prompt":"p",
                       "faultInjection":{"find":"'@/components/","replace":"'@/components-broken/"}}]}
                    """;

            EvalSet set = parseWith(json);

            EvalCase.FaultInjection fi = set.getCases().get(0).getFaultInjection();
            assertThat(fi).isNotNull();
            assertThat(fi.getFile()).isNull();
            assertThat(fi.getFind()).isEqualTo("'@/components/");
        }

        @Test
        @DisplayName("完整配置应逐字段解析出来")
        void shouldParseCompleteConfig() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"vue_project","prompt":"p",
                       "faultInjection":{"file":"src/App.vue",
                                         "find":"'@/components/",
                                         "replace":"'@/components-broken/"}}]}
                    """;

            EvalSet set = parseWith(json);

            EvalCase.FaultInjection fi = set.getCases().get(0).getFaultInjection();
            assertThat(fi).isNotNull();
            assertThat(fi.getFile()).isEqualTo("src/App.vue");
            assertThat(fi.getFind()).isEqualTo("'@/components/");
            assertThat(fi.getReplace()).isEqualTo("'@/components-broken/");
        }

        @Test
        @DisplayName("find / replace 缺任何一个都应报错，不能宽容成「无注入」")
        void shouldRejectIncompleteConfig() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"vue_project","prompt":"p",
                       "faultInjection":{"file":"src/App.vue"}}]}
                    """;

            // 宽容处理会让注入被静默跳过 → 首轮直接通过 → 报告显示漂亮数据，
            // 而这条用例存在的唯一意义（验证自愈）已经悄悄消失。
            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("faultInjection 缺少字段")
                    .hasMessageContaining("find")
                    .hasMessageContaining("replace");
        }

        @Test
        @DisplayName("file 不允许跳出项目目录，否则就等于随机改磁盘上的文件")
        void shouldRejectEscapingPath() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"vue_project","prompt":"p",
                       "faultInjection":{"file":"../../etc/hosts","find":"a","replace":"b"}}]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不允许跳出项目目录");
        }

        @Test
        @DisplayName("file 不允许是绝对路径")
        void shouldRejectAbsolutePath() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"vue_project","prompt":"p",
                       "faultInjection":{"file":"C:/Windows/system.ini","find":"a","replace":"b"}}]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("必须是相对路径");
        }

        @Test
        @DisplayName("替换前后内容相同应报错 —— 那是配置写错了，不是有效注入")
        void shouldRejectNoOpReplacement() {
            String json = """
                    {"name":"t","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"vue_project","prompt":"p",
                       "faultInjection":{"file":"src/App.vue","find":"same","replace":"same"}}]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("替换前后内容相同");
        }
    }

    @Nested
    @DisplayName("校验规则")
    class Validation {

        @Test
        @DisplayName("缺少 version 应报错 —— 没有版本就无法跨批次比较指标")
        void shouldRejectMissingVersion() {
            String json = """
                    {"name":"无版本","cases":[
                      {"id":"a","description":"d","codeGenType":"html","prompt":"p"}]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("version");
        }

        @Test
        @DisplayName("用例列表为空应报错")
        void shouldRejectEmptyCases() {
            String json = """
                    {"name":"空集","version":"1.0","cases":[]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("不包含任何用例");
        }

        @Test
        @DisplayName("id 重复应报错并指出是哪个 id")
        void shouldRejectDuplicateId() {
            String json = """
                    {"name":"重复","version":"1.0","cases":[
                      {"id":"dup","description":"d","codeGenType":"html","prompt":"p"},
                      {"id":"dup","description":"d","codeGenType":"html","prompt":"p"}]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("用例 id 重复：dup");
        }

        @Test
        @DisplayName("id 含路径分隔符应被拒绝，防止产物目录被拼到别处")
        void shouldRejectUnsafeId() {
            String json = """
                    {"name":"危险 id","version":"1.0","cases":[
                      {"id":"../escape","description":"d","codeGenType":"html","prompt":"p"}]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("只允许字母、数字");
        }

        @Test
        @DisplayName("prompt 为空应报错")
        void shouldRejectBlankPrompt() {
            String json = """
                    {"name":"空 prompt","version":"1.0","cases":[
                      {"id":"a","description":"d","codeGenType":"html","prompt":"  "}]}
                    """;

            assertThatThrownBy(() -> parseWith(json))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("缺少 prompt");
        }

        @Test
        @DisplayName("内容为空应报错")
        void shouldRejectBlankContent() {
            assertThatThrownBy(() -> parseWith("   "))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("内容为空");
        }
    }
}
