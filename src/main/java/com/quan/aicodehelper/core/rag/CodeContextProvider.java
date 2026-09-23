package com.quan.aicodehelper.core.rag;

import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索增强的对外唯一入口，一次调用拿到要注入的项目上下文文本。调用方只需字符串，不关心召回细节；
 * 检索失败降级返回空串，永不抛出。
 */
@Slf4j
@Service
public class CodeContextProvider {

    @Resource
    private CodeRetriever codeRetriever;

    @Resource
    private ProjectContextBuilder projectContextBuilder;

    /**
     * 构建注入 system message 的项目上下文；无可注入内容返回空串。
     *
     * @param userMessage 用户需求，同时作为检索查询
     */
    public String buildContext(long appId, CodeGenTypeEnum type, String userMessage) {
        if (type == null || userMessage == null || userMessage.isBlank()) {
            return "";
        }
        try {
            List<RetrievedSlice> slices = codeRetriever.retrieve(appId, type, userMessage);
            return projectContextBuilder.build(type, slices);
        } catch (Exception e) {
            log.warn("应用 {} 构建项目上下文失败（本轮不做检索增强）：{}", appId, e.getMessage());
            return "";
        }
    }
}
