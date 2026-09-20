package com.quan.aicodehelper;

import com.quan.aicodehelper.ai.service.AiCodeGenTypeRoutingService;
import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import com.quan.aicodehelper.utils.WebScreenshotUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用上下文与外部依赖连通性冒烟测试。
 *
 * <p><b>注意</b>：本类中的方法会真实调用外部依赖（大模型 API、Chrome 浏览器），
 * 因此不适合纳入常规 CI 流程 —— 它们依赖网络、消耗额度且耗时较长。
 * 仅用于本地联调时快速确认「上下文能启动、外部依赖配置正确」。</p>
 *
 * <p>如需默认跳过，可执行：{@code mvn test -Dtest=!AiCodeHelperApplicationTests}
 * 或为本类添加 {@code @Disabled}。</p>
 *
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Slf4j
@SpringBootTest
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
