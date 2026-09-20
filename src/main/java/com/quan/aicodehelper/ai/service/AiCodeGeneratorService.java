package com.quan.aicodehelper.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public interface AiCodeGeneratorService {
    String generateCode(String userMessage);

    /**
     * 流式生成HTML代码
     * @param userMessage 用户消息
     * @return 生成代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-html-system-prompt.txt")
    Flux<String> generateHtmlCodeStream(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 流式生成多文件代码
     * @param userMessage 用户消息
     * @return 生成代码结果
     */
    @SystemMessage(fromResource = "prompt/codegen-multi-file-system-prompt.txt")
    Flux<String> generateMultiFileCodeStream(@MemoryId Long appId, @UserMessage String userMessage);

    /**
     * 流式生成Vue项目代码
     * @param appId
     * @param userMessage
     * @return
     */
    @SystemMessage(fromResource = "prompt/codegen-vue-project-system-prompt.txt")
    Flux<String> generateVueProjectCodeStream(@MemoryId Long appId, @UserMessage String userMessage);
}


