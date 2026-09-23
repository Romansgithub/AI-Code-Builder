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
     * 流式生成HTML代码
     *
     * @param appId          应用 id，用于隔离对话记忆
     * @param projectContext 从项目现有代码中检索到的相关片段（RAG）。
     *                       它通过模板变量注入 <b>system message</b>，而不是拼进 userMessage ——
     *                       生成服务挂着输入护栏（1000 字上限 + 注入模式检测），
     *                       拼接检索内容会被直接拒绝。详见 {@code ProjectContextBuilder}。
     *                       无相关片段时传空串。
     * @param userMessage    用户消息
     * @return 生成代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(@MemoryId Long appId,
                                        @V("projectContext") String projectContext,
                                        @UserMessage String userMessage);

    /**
     * 流式生成多文件代码
     *
     * @param appId          应用 id
     * @param projectContext 检索到的项目现有代码片段，注入 system message
     * @param userMessage    用户消息
     * @return 生成代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(@MemoryId Long appId,
                                             @V("projectContext") String projectContext,
                                             @UserMessage String userMessage);

    /**
     * 流式生成Vue项目代码
     *
     * @param appId          应用 id
     * @param projectContext 检索到的项目现有代码片段，注入 system message
     * @param userMessage    用户消息
     * @return 生成代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    Flux<String> generateVueProjectCodeStream(@MemoryId Long appId,
                                              @V("projectContext") String projectContext,
                                              @UserMessage String userMessage);
}
