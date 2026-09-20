package com.quan.aicodehelper;

import com.quan.aicodehelper.ai.service.AiCodeGenTypeRoutingService;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.utils.WebScreenshotUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 应用上下文与外部依赖连通性冒烟测试。
 *
 * <p><b>注意</b>：标了 {@code @Disabled} 的方法会真实调用外部依赖（大模型 API、Chrome 浏览器），
 * 因此不纳入常规 CI —— 它们依赖网络、消耗额度且耗时较长。
 * 需要本地联调时，手动去掉 {@code @Disabled} 或从 IDE 单独运行即可。</p>
 *
 * <p>上下文加载用例（{@link #contextLoads()}）使用 test profile，
 * 由 {@code src/test/resources/application-test.yml} 提供全部假配置，
 * 因此不依赖任何真实环境变量，可在干净环境与 CI 中稳定通过。</p>
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
class AiCodeHelperApplicationTests {

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    /**
     * 冒烟验证：上下文可正常加载。
     * 这是本类唯一不依赖外部服务的测试，适合常驻执行。
     */
    @Test
    void contextLoads() {
        Assertions.assertNotNull(aiCodeGenTypeRoutingService,
                "路由服务应被 Spring 正确注入");
    }

    /**
     * 手工冒烟：验证网页截图能力（依赖 Chrome 与本机网络）。
     *
     * <p>调用真实浏览器打开页面并截图，用于确认 Selenium 与驱动配置正确。</p>
     */
    @Test
    @Disabled("手工冒烟：依赖 Chrome 与本机网络，不纳入常规测试")
    void smokeTestWebScreenshot() {
        String testUrl = "https://www.baidu.com";
        String webPageScreenshot = WebScreenshotUtils.saveWebPageScreenshot(testUrl);
        Assertions.assertNotNull(webPageScreenshot, "截图结果路径不应为空");
        log.info("截图已保存至：{}", webPageScreenshot);
    }

    /**
     * 手工冒烟：验证大模型路由能力（依赖大模型 API 与额度）。
     *
     * <p>覆盖三种典型需求，确认模型能区分单文件 / 多文件 / Vue 工程三种生成类型。</p>
     */
    @Test
    @Disabled("手工冒烟：真实调用大模型 API，消耗额度且耗时，不纳入常规测试")
    void smokeTestRouteCodeGenType() {
        String[] prompts = {
                "做一个简单的个人介绍页面",
                "做一个公司官网，需要首页、关于我们、联系我们三个页面",
                "做一个电商管理系统，包含用户管理、商品管理、订单管理，需要路由和状态管理"
        };
        for (String prompt : prompts) {
            CodeGenTypeEnum result = aiCodeGenTypeRoutingService.routeCodeGenType(prompt);
            Assertions.assertNotNull(result, "路由结果不应为空：" + prompt);
            log.info("路由结果：{} -> {}", prompt, result.getValue());
        }
    }
}
