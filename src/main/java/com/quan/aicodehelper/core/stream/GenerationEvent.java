package com.quan.aicodehelper.core.stream;

/**
 * 一条可补发的生成进度事件。
 *
 * <p><b>为什么要带 id</b>：断线重连后前端只会说「我最后收到的是 7 号」，
 * 服务端必须能据此找出 8 号及之后的事件。因此 id 必须是<b>单调递增</b>的，
 * 且要在放入缓冲时就固定下来（不能等到下发时才算）——
 * 否则同一个事件在两次读取时可能拿到不同 id，补发逻辑立刻失效。</p>
 *
 * @param id      事件序号，从 1 开始，同一 appId 内单调递增
 * @param event   事件名（progress / done / error / business-error），与前端监听名一致
 * @param data    事件负载，已经是 JSON 字符串
 * @param created 事件产生时刻（毫秒），用于任务保留期清理与问题定位
 */
public record GenerationEvent(long id, String event, String data, long created) {

    public static GenerationEvent of(long id, String event, String data) {
        return new GenerationEvent(id, event, data, System.currentTimeMillis());
    }
}
