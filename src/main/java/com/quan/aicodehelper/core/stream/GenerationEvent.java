package com.quan.aicodehelper.core.stream;

/**
 * 一条可补发的生成进度事件。id 必须在放入缓冲时固定，不能等到下发才算，否则同一事件
 * 两次读取可能拿到不同 id，断线补发失效。事件名与前端监听名一致
 * （progress / done / error / business-error），created 为毫秒。
 */
public record GenerationEvent(long id, String event, String data, long created) {

    public static GenerationEvent of(long id, String event, String data) {
        return new GenerationEvent(id, event, data, System.currentTimeMillis());
    }
}
