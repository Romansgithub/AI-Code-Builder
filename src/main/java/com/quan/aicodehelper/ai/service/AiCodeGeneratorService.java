package com.quan.aicodehelper.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public interface AiCodeGeneratorService {
    String generateCode(String userMessage);

    /**
     * 流式生成 HTML 代码。
     * @param projectContext 项目现有代码片段，注入 system message 而非拼进 userMessage：生成服务挂输入护栏
     *                       （1000 字上限 + 注入模式检测），拼进 userMessage 会被直接拒绝；无片段时传空串。
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(@MemoryId Long appId,
                                        @V("projectContext") String projectContext,
                                        @UserMessage String userMessage);

    /** 流式生成多文件代码。{@code projectContext} 注入 system message，理由同 generateHtmlCodeStream。 */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(@MemoryId Long appId,
                                             @V("projectContext") String projectContext,
                                             @UserMessage String userMessage);

    /** 流式生成 Vue 项目代码。{@code projectContext} 注入 system message，理由同 generateHtmlCodeStream。 */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    Flux<String> generateVueProjectCodeStream(@MemoryId Long appId,
                                              @V("projectContext") String projectContext,
                                              @UserMessage String userMessage);
}
