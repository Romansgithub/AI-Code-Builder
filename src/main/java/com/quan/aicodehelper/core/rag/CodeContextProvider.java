package com.quan.aicodehelper.core.rag;

import com.quan.aicodehelper.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索增强的对外唯一入口：一次调用拿到「该注入给模型的项目上下文文本」。
 *
 * <p><b>为什么要有这一层</b>：调用方（代码生成门面）只需要一个字符串，
 * 不该知道「先检索再渲染」这两步，更不该知道里面用了几路召回、怎么配额。
 * 把「取上下文」收成一个方法，将来换召回策略（加 BM25、加重排模型）
 * 都不会波及生成链路。</p>
 *
 * <p><b>永不抛出</b>：检索增强失败的正确后果是「这一轮没有增强」，
 * 而不是「用户拿不到代码」。这个契约写在方法签名里 —— 返回空串即可。</p>
 */
@Slf4j
@Service
public class CodeContextProvider {

    @Resource
    private CodeRetriever codeRetriever;

    @Resource
    private ProjectContextBuilder projectContextBuilder;

    /**
     * 构建要注入 system message 的项目上下文。
     *
     * @param appId       应用 id
     * @param type        代码生成类型
     * @param userMessage 用户本次需求，同时作为检索查询
     * @return 上下文文本；无可注入内容时返回空串（调用方直接透传即可）
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
