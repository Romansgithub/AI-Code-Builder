package com.quan.aicodehelper.controller;

import com.quan.aicodehelper.ai.tools.GenerationProgressRegistry;
import com.quan.aicodehelper.core.stream.GenerationTask;
import com.quan.aicodehelper.model.entity.App;
import com.quan.aicodehelper.model.entity.User;
import com.quan.aicodehelper.service.AppService;
import com.quan.aicodehelper.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 生成接口拆分后的 HTTP 行为测试：用 MockMvc 从 HTTP 边界断言触发接口是 POST 收 body、
 * 订阅接口是 GET 读 Last-Event-ID、触发返回 JSON 回执。service 层测不出这类信息。
 */
@DisplayName("代码生成接口（触发 / 订阅分离）")
class AppGenStreamControllerTest {

    private static final Long APP_ID = 6001L;
    private static final Long USER_ID = 100L;

    private AppController controller;
    private AppService appService;
    private UserService userService;
    private GenerationProgressRegistry registry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        appService = mock(AppService.class);
        userService = mock(UserService.class);
        registry = mock(GenerationProgressRegistry.class);

        controller = new AppController();
        ReflectionTestUtils.setField(controller, "appService", appService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "generationProgressRegistry", registry);

            // standaloneSetup 默认不带全局异常处理器，参数校验用例会收到 ServletException 而非真实 JSON 响应
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new com.quan.aicodehelper.exception.GlobalExceptionHandler())
                .setAsyncRequestTimeout(Long.MAX_VALUE)
                .build();
    }

    private User loginUser() {
        User user = new User();
        user.setId(USER_ID);
        return user;
    }

    @Nested
    @DisplayName("发起生成")
    class Trigger {

        @Test
        @DisplayName("触发接口的 HTTP 方法必须是 POST —— 改成 GET 就会重新引入重复生成")
        void shouldBePost() throws Exception {
            List<Method> matches = new ArrayList<>();
            for (Method m : AppController.class.getDeclaredMethods()) {
                if ("generateCode".equals(m.getName())) {
                    matches.add(m);
                }
            }
            assertThat(matches).hasSize(1);
            Method generateCode = matches.get(0);

            boolean hasPostMapping = Arrays.stream(generateCode.getAnnotations())
                    .anyMatch(a -> a.annotationType().getSimpleName().equals("PostMapping"));
            boolean hasGetMapping = Arrays.stream(generateCode.getAnnotations())
                    .anyMatch(a -> a.annotationType().getSimpleName().equals("GetMapping"));

            assertThat(hasPostMapping)
                    .as("触发接口写操作必须挂在 POST 上，否则浏览器会自动重放请求导致重复生成")
                    .isTrue();
            assertThat(hasGetMapping)
                    .as("同一个方法不能同时是 GET，避免误配成可重放读接口")
                    .isFalse();
        }

        @Test
        @DisplayName("POST 发起生成应返回 JSON 回执，且内容为 running")
        void shouldReturnJsonReceipt() throws Exception {
            when(userService.getLoginUser(any())).thenReturn(loginUser());
            GenerationTask task = new GenerationTask(APP_ID);
            when(appService.startGeneration(anyLong(), anyString(), any())).thenReturn(task);

            mockMvc.perform(post("/app/chat/gen/code")
                            .contentType("application/json")
                            .content("{\"appId\":6001,\"message\":\"做一个登录页\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.appId").value(APP_ID))
                    .andExpect(jsonPath("$.data.status").value("running"))
                    .andExpect(jsonPath("$.data.statusDescription").value("生成中"));
        }

        @Test
        @DisplayName("复用已有任务时回执应标记 reused，便于前端提示「已为你接续」")
        void shouldFlagReusedTask() throws Exception {
            when(userService.getLoginUser(any())).thenReturn(loginUser());
            // 构造一个「请求进来之前就存在」的任务，模拟并发/重连命中复用路径
            GenerationTask existing = new GenerationTask(APP_ID);
            Thread.sleep(5);
            when(appService.startGeneration(anyLong(), anyString(), any())).thenReturn(existing);

            mockMvc.perform(post("/app/chat/gen/code")
                            .contentType("application/json")
                            .content("{\"appId\":6001,\"message\":\"再改一下\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reused").value(true));
        }

        @Test
        @DisplayName("appId 或 message 为空时应被参数校验拦下，不得进入生成流程")
        void shouldRejectBlankParams() throws Exception {
            mockMvc.perform(post("/app/chat/gen/code")
                            .contentType("application/json")
                            .content("{\"appId\":0,\"message\":\"x\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40000));

            mockMvc.perform(post("/app/chat/gen/code")
                            .contentType("application/json")
                            .content("{\"appId\":6001,\"message\":\"  \"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(40000));

            verify(appService, never()).startGeneration(anyLong(), anyString(), any());
        }

        @Test
        @DisplayName("旧的长连接触发方式（GET + query）不应再存在")
        void shouldNotExposeLongLivedGetTrigger() throws Exception {
            // 旧的长连接 GET 触发方式必须彻底消失，否则浏览器会重新自动重放它
            boolean hasGetOnTrigger = Arrays.stream(AppController.class.getDeclaredMethods())
                    .filter(m -> "generateCode".equals(m.getName()))
                    .flatMap(m -> Arrays.stream(m.getAnnotations()))
                    .anyMatch(a -> a.annotationType().getSimpleName().equals("GetMapping"));
            assertThat(hasGetOnTrigger).isFalse();
        }
    }

    @Nested
    @DisplayName("订阅生成进度")
    class Subscribe {

        @Test
        @DisplayName("订阅接口应为 GET，且路径与触发接口不同")
        void shouldExposeGetStream() throws Exception {
            List<Method> matches = new ArrayList<>();
            for (Method m : AppController.class.getDeclaredMethods()) {
                if ("subscribeGenStream".equals(m.getName())) {
                    matches.add(m);
                }
            }
            assertThat(matches).hasSize(1);
            boolean hasGetMapping = Arrays.stream(matches.get(0).getAnnotations())
                    .anyMatch(a -> a.annotationType().getSimpleName().equals("GetMapping"));
            boolean producesSse = Arrays.stream(matches.get(0).getAnnotations())
                    .filter(a -> a.annotationType().getSimpleName().equals("GetMapping"))
                    .flatMap(a -> {
                        try {
                            Object value = a.annotationType().getMethod("produces").invoke(a);
                            return Arrays.stream((String[]) value);
                        } catch (Exception e) {
                            return Arrays.stream(new String[0]);
                        }
                    })
                    .anyMatch("text/event-stream"::equals);
            assertThat(hasGetMapping).isTrue();
            assertThat(producesSse).as("订阅接口必须声明 SSE 媒体类型").isTrue();
        }

        @Test
        @DisplayName("Last-Event-ID 请求头应被解析为续传起点，并透传给订阅逻辑")
        void shouldPassLastEventIdFromHeader() throws Exception {
            when(userService.getLoginUser(any())).thenReturn(loginUser());
            when(registry.subscribe(anyLong(), anyLong())).thenReturn(Flux.empty());

            mockMvc.perform(get("/app/chat/gen/stream")
                            .param("appId", String.valueOf(APP_ID))
                            .header("Last-Event-ID", "7"))
                    .andExpect(status().isOk());

            verify(registry).subscribe(APP_ID, 7L);
        }

        @Test
        @DisplayName("query 参数可作为续传起点的补充（无请求头时生效）")
        void shouldFallbackToQueryParam() throws Exception {
            when(userService.getLoginUser(any())).thenReturn(loginUser());
            when(registry.subscribe(anyLong(), anyLong())).thenReturn(Flux.empty());

            mockMvc.perform(get("/app/chat/gen/stream")
                            .param("appId", String.valueOf(APP_ID))
                            .param("lastEventId", "3"))
                    .andExpect(status().isOk());

            verify(registry).subscribe(APP_ID, 3L);
        }

        @Test
        @DisplayName("请求头优先于 query，且非法请求头不得导致 500")
        void shouldPreferHeaderAndTolerateGarbage() throws Exception {
            when(userService.getLoginUser(any())).thenReturn(loginUser());
            when(registry.subscribe(anyLong(), anyLong())).thenReturn(Flux.empty());

            mockMvc.perform(get("/app/chat/gen/stream")
                            .param("appId", String.valueOf(APP_ID))
                            .param("lastEventId", "3")
                            .header("Last-Event-ID", "9"))
                    .andExpect(status().isOk());
            verify(registry).subscribe(APP_ID, 9L);

            // 非数字的 Last-Event-ID（代理或客户端实现差异导致）应回退到 query，而不是抛异常
            mockMvc.perform(get("/app/chat/gen/stream")
                            .param("appId", String.valueOf(APP_ID))
                            .param("lastEventId", "4")
                            .header("Last-Event-ID", "not-a-number"))
                    .andExpect(status().isOk());
            verify(registry).subscribe(APP_ID, 4L);
        }

        @Test
        @DisplayName("无权访问该应用时，订阅应收到 business-error 而不是别人的进度")
        void shouldRejectUnauthorizedSubscription() throws Exception {
            when(userService.getLoginUser(any())).thenReturn(loginUser());
            org.mockito.Mockito.doThrow(new com.quan.aicodehelper.exception.BusinessException(
                            com.quan.aicodehelper.exception.ErrorCode.NO_AUTH_ERROR, "无权访问该应用"))
                    .when(appService).checkAppPermission(anyLong(), any());

            mockMvc.perform(get("/app/chat/gen/stream").param("appId", String.valueOf(APP_ID)))
                    .andExpect(status().isOk());

            // 越权时绝不能去订阅，否则等于把别人的生成过程直播出去
            verify(registry, never()).subscribe(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("生成状态查询")
    class Status {

        @Test
        @DisplayName("有任务时应回报状态与运行标志，供刷新页面后恢复现场")
        void shouldReportRunningStatus() throws Exception {
            when(userService.getLoginUser(any())).thenReturn(loginUser());
            GenerationTask task = new GenerationTask(APP_ID);
            when(registry.getTask(APP_ID)).thenReturn(task);

            mockMvc.perform(get("/app/chat/gen/status").param("appId", String.valueOf(APP_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasTask").value(true))
                    .andExpect(jsonPath("$.data.running").value(true))
                    .andExpect(jsonPath("$.data.status").value("running"));
        }

        @Test
        @DisplayName("无任务时应明确回报 hasTask=false，前端据此避免误发起新生成")
        void shouldReportNoTask() throws Exception {
            when(userService.getLoginUser(any())).thenReturn(loginUser());
            when(registry.getTask(APP_ID)).thenReturn(null);

            mockMvc.perform(get("/app/chat/gen/status").param("appId", String.valueOf(APP_ID)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasTask").value(false))
                    .andExpect(jsonPath("$.data.running").value(false));
        }
    }
}
