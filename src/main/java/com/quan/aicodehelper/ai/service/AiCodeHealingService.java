package com.quan.aicodehelper.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * 构建自愈专用的 AI 服务。不复用 {@link AiCodeGeneratorService}：其护栏（1000 字上限 + 注入检测）面向聊天框输入，
 * 而自愈的 user 消息是程序拼装的构建报错，超长会被直接拒绝，自愈静默失效（重试仍被拒、报告却显示「自愈 N 轮」）。
 * 拆开后修复指令进 system、构建报错进 user，长度只随报错增长。详见 docs/design-notes.md 第一节。
 * @see com.quan.aicodehelper.core.healing.BuildSelfHealingService
 */
public interface AiCodeHealingService {

    /**
     * 让模型修复导致构建失败的问题。复用同一 appId 的会话记忆，模型因此能看到自己刚写的文件，不必重读整段上下文。
     * @param buildFailureReport 构建失败报告（失败阶段 + 提取后的报错），由 BuildSelfHealingService 组装并按 maxErrorLength 截断
     */
    @SystemMessage(fromResource = "prompt/build-self-healing-system-prompt.txt")
    Flux<String> healVueProjectBuildFailure(@MemoryId Long appId,
                                            @UserMessage String buildFailureReport);
}
