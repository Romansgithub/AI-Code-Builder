package com.quan.aicodehelper.ai.service;

import com.quan.aicodehelper.ai.guardrail.PromptSafetyInputGuardrail;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.GuardrailException;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 检索上下文注入位置的端到端验证。
 *
 * <p><b>为什么需要这个测试，而不是靠 RAG 自己的单测</b>：RAG 那一串类（扫描 / 切块 / 检索 /
 * 渲染）都只保证「生成了正确的上下文文本」。而检索结果究竟有没有真正进到发给模型的请求里、
 * 进的是哪一条 message，取决于 {@code AiServices} 对 {@code @SystemMessage(fromResource=...)}
 * 里 {@code {{projectContext}}} 的渲染行为 —— 这段逻辑一行都不在我们自己的代码里。
 * 它要是没生效，表现为「检索算了、钱花了、模型没看到」，而所有单测依然全绿。</p>
 *
 * <p>所以这里用真实的 prompt 资源文件 + 生产同款输入护栏搭一个 {@code AiCodeGeneratorService}，
 * mock 掉 {@link StreamingChatModel} 把实际发出的 {@link ChatRequest} 截住，
 * 直接断言两条 message 的内容。</p>
 */
@DisplayName("检索上下文注入 system message")
class AiCodeGeneratorServicePromptInjectionTest {

    private static final Long APP_ID = 3101L;
    private static final String USER_MESSAGE = "把登录按钮改成蓝色";
    /** 带路径与行号的检索产物，模拟 ProjectContextBuilder 的真实输出。 */
    private static final String CONTEXT = """
            以下是从该项目现有代码中检索到的相关片段（来自磁盘上的真实代码，不是输出格式示例）：
            [文件] src/views/Login.vue（第 12-24 行）
            ```vue
            <template>
              <button class="login-btn">登录</button>
            </template>
            ```""";

    /** 每次被调用时截获的请求，模型不会真的被调用。 */
    private final List<ChatRequest> captured = new CopyOnWriteArrayList<>();

    /**
     * 用生产同款配置搭一个真服务，只把模型换成 mock。
     *
     * <p>刻意保留 {@link PromptSafetyInputGuardrail}：它只审视 user message。
     * 检索内容一旦被误拼进 user message，护栏会直接拒掉请求 —— 本测试就是要守住这条线。</p>
     */
    private AiCodeGeneratorService newService() {
        StreamingChatModel model = mock(StreamingChatModel.class);
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            StreamingChatResponseHandler handler = invocation.getArgument(1);
            handler.onPartialResponse("ok");
            handler.onCompleteResponse(
                    ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());
            return null;
        }).when(model).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));

        return AiServices.builder(AiCodeGeneratorService.class)
                .streamingChatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .inputGuardrails(new PromptSafetyInputGuardrail())
                .build();
    }

    private ChatRequest onlyRequest() {
        assertThat(captured).as("模型应当被调用且只调用一次").hasSize(1);
        return captured.get(0);
    }

    private static String systemText(ChatRequest request) {
        return request.messages().stream()
                .filter(SystemMessage.class::isInstance)
                .map(SystemMessage.class::cast)
                .map(SystemMessage::text)
                .findFirst()
                .orElseThrow(() -> new AssertionError("请求里没有 system message"));
    }

    private static String userText(ChatRequest request) {
        return request.messages().stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .map(UserMessage::singleText)
                .findFirst()
                .orElseThrow(() -> new AssertionError("请求里没有 user message"));
    }

    /** 检索内容进了 system message，且用户原话没被污染。 */
    private void assertInjectedIntoSystemMessage() {
        ChatRequest request = onlyRequest();
        assertThat(systemText(request))
                .as("检索片段必须出现在 system message 里，否则这次检索等于没做")
                .contains("[文件] src/views/Login.vue（第 12-24 行）")
                .contains("login-btn")
                .as("占位符必须已被替换，留下字面量说明模板没渲染")
                .doesNotContain("{{projectContext}}");
        assertThat(userText(request))
                .as("user message 必须保持用户原话")
                .isEqualTo(USER_MESSAGE);
    }

    @Nested
    @DisplayName("三种生成类型都注入了")
    class Injected {

        @Test
        @DisplayName("HTML 模式")
        void html() {
            newService().generateHtmlCodeStream(APP_ID, CONTEXT, USER_MESSAGE)
                    .collectList().block(Duration.ofSeconds(10));
            assertInjectedIntoSystemMessage();
        }

        @Test
        @DisplayName("多文件模式")
        void multiFile() {
            newService().generateMultiFileCodeStream(APP_ID, CONTEXT, USER_MESSAGE)
                    .collectList().block(Duration.ofSeconds(10));
            assertInjectedIntoSystemMessage();
        }

        @Test
        @DisplayName("Vue 工程模式")
        void vueProject() {
            newService().generateVueProjectCodeStream(APP_ID, CONTEXT, USER_MESSAGE)
                    .collectList().block(Duration.ofSeconds(10));
            assertInjectedIntoSystemMessage();
        }
    }

    @Nested
    @DisplayName("无检索结果时")
    class EmptyContext {

        @Test
        @DisplayName("空串占位符应被替换为空，模板不能留字面量、也不能抛异常")
        void rendersEmpty() {
            newService().generateHtmlCodeStream(APP_ID, "", USER_MESSAGE)
                    .collectList().block(Duration.ofSeconds(10));

            String system = systemText(onlyRequest());
            assertThat(system)
                    .as("模板里的 {{...}} 应当全部渲染掉")
                    .doesNotContain("{{")
                    .doesNotContain("projectContext");
            assertThat(system)
                    .as("system message 的正文仍应在")
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("护栏边界")
    class GuardrailBoundary {

        /**
         * 这条是整份设计的理由所在：真实检索上下文动辄几千字，
         * 走 user message 会被 1000 字护栏当场拒掉。下面的用例先证明护栏在本测试里
         * 确实是生效的（否则上一条测试就是空转），再证明大上下文走 system message 平安通过。
         */
        @Test
        @DisplayName("超长内容走 user message 会被护栏拒绝 —— 证明护栏确实生效")
        void guardrailIsActive() {
            assertThatThrownBy(() -> newService()
                    .generateHtmlCodeStream(APP_ID, "", "做一个登录页".repeat(300)))
                    .isInstanceOf(GuardrailException.class);
            assertThat(captured).as("被护栏拦下时不该调用模型").isEmpty();
        }

        @Test
        @DisplayName("三千字的检索上下文不应触发 1000 字护栏，模型照常被调用")
        void longContextPasses() {
            String longContext = CONTEXT + "\n" + "x".repeat(3000);

            newService().generateHtmlCodeStream(APP_ID, longContext, USER_MESSAGE)
                    .collectList().block(Duration.ofSeconds(10));

            ChatRequest request = onlyRequest();
            String system = systemText(request);
            assertThat(system)
                    .as("整段上下文应完整送达，而不是被截在 system message 之外")
                    .contains("x".repeat(3000));
            assertThat(userText(request))
                    .as("护栏只看 user message，所以大上下文不会牵连用户输入")
                    .isEqualTo(USER_MESSAGE);
        }
    }
}
