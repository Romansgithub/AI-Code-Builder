package com.quan.aicodehelper.ai.config;

import cn.hutool.extra.spring.SpringUtil;
import com.quan.aicodehelper.ai.service.AiCodeGenTypeRoutingService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

@Configuration
@ConfigurationProperties(prefix = "langchain4j.open-ai.routing-chat-model")
@Slf4j
@Data
public class AiCodeGenTypeRoutingConfig
{
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private int maxTokens;
    private double temperature;
    private boolean logRequests = false;
    private boolean logResponses = false;

    public AiCodeGenTypeRoutingService createAiCodeGenTypeRoutingService()
    {
        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .maxTokens(maxTokens)
                .temperature(temperature)
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build()
                ;
        return AiServices.builder(AiCodeGenTypeRoutingService.class)
                .chatModel(chatModel)
                .build();
    }

    @Bean
    @Scope("prototype")
    public AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService()
    {
        return createAiCodeGenTypeRoutingService();
    }
}
