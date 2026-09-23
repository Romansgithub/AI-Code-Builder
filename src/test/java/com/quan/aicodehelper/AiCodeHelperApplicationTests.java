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
 * 应用上下文与外部依赖连通性冒烟测试。标了 {@code @Disabled} 的方法真实调用外部依赖
 * （大模型 API、Chrome），不纳入常规 CI；上下文加载用例用 test profile 的假配置。
 * @author <a href="https://github.com/romansgithub">Roman</a>
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("test")
class AiCodeHelperApplicationTests {

    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    /** 唯一不依赖外部服务的常驻冒烟：上下文可正常加载 */
    @Test
    void contextLoads() {
        Assertions.assertNotNull(aiCodeGenTypeRoutingService,
                "路由服务应被 Spring 正确注入");
    }

    /** 手工冒烟：真实调用 Chrome 截图，依赖本机浏览器与网络 */
    @Test
    @Disabled("手工冒烟：依赖 Chrome 与本机网络，不纳入常规测试")
    void smokeTestWebScreenshot() {
        String testUrl = "https://www.baidu.com";
        String webPageScreenshot = WebScreenshotUtils.saveWebPageScreenshot(testUrl);
        Assertions.assertNotNull(webPageScreenshot, "截图结果路径不应为空");
        log.info("截图已保存至：{}", webPageScreenshot);
    }

    /** 手工冒烟：真实调用大模型路由，消耗额度与 API 调用 */
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
