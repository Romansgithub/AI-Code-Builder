package com.quan.aicodehelper.ai.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * 构建自愈专用的 AI 服务。
 *
 * <p><b>为什么不复用 {@link AiCodeGeneratorService}</b>：那个服务的 user 消息来自
 * 聊天框，所以挂了 {@code PromptSafetyInputGuardrail} 做「长度 + 注入攻击」检查。
 * 而自愈的 user 消息是程序拼出来的构建报错 —— 送进同一个服务，就等于让
 * 「用户输入不要超过 1000 字」这条面向聊天框的规则去约束一段机器生成的文本。</p>
 *
 * <p><b>踩过的坑（这是本接口存在的直接原因）</b>：自愈提示词（模板 + 构建报错）
 * 合计 1241 字，超过护栏 1000 字上限，于是<b>请求根本没发出去</b>，
 * 异常被 {@code catch (Exception)} 吞成「这轮没修好」，外层再 continue 重试一轮，
 * 又被同样拒一次。跑批报告上写着「自愈 2 轮」，而模型一次都没被调用过 ——
 * 耗时也对不上（生成 92.2s + 构建 9.5s ≈ 总耗时 101.8s，所谓「自愈两轮」零耗时），
 * Redis 会话记忆里也找不到那两轮的痕迹。</p>
 *
 * <p>更隐蔽的是配置层面的矛盾：{@code app.self-healing.max-error-length=3000}
 * 明确允许喂 3000 字报错，而护栏要求 user 消息不超过 1000 字 ——
 * <b>报错只要超过 332 字，两边就必然冲突</b>。两处配置各自看都合理，
 * 谁也不知道对方存在，于是静默失效：报错 573 字，命中率 100%，被拒率 100%。</p>
 *
 * <p><b>拆开之后语义也顺了</b>：修复指令是「规则」，属于 system 消息；
 * 只有构建报错才是这次请求的「输入」，进 user 消息。这样 user 消息的长度
 * 只随报错增长，不再受指令措辞变化牵连。</p>
 *
 * @see com.quan.aicodehelper.core.healing.BuildSelfHealingService
 */
public interface AiCodeHealingService {

    /**
     * 让模型修复导致构建失败的问题。
     *
     * <p>复用同一个 {@code appId} 的会话记忆，模型因此能看到自己刚写下的文件，
     * 不必重新读取整个上下文。</p>
     *
     * @param appId              应用 ID，同时作为会话记忆 ID
     * @param buildFailureReport 构建失败报告（失败阶段 + 提取后的报错）。
     *                           由 {@code BuildSelfHealingService} 组装，
     *                           并按 {@code maxErrorLength} 做过长度截断
     * @return 流式回复（正常只有一句简短说明，中间夹杂工具调用过程）
     */
    @SystemMessage(fromResource = "prompt/build-self-healing-system-prompt.txt")
    Flux<String> healVueProjectBuildFailure(@MemoryId Long appId,
                                            @UserMessage String buildFailureReport);
}
