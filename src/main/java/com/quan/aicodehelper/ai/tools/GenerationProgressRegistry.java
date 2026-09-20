package com.quan.aicodehelper.ai.tools;

import cn.hutool.json.JSONUtil;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成进度注册类
 */
@Component
public class GenerationProgressRegistry {
    private final Map<Long, Sinks.Many<ServerSentEvent<String>>> sinks = new ConcurrentHashMap<>();

    public Sinks.Many<ServerSentEvent<String>> register(Long appId)
    {
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().unicast().onBackpressureBuffer();
        sinks.put(appId, sink);
        return sink;
    }

    public void emit(Long appId, String message)
    {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(appId);
        if (sink != null)
        {
            sink.tryEmitNext(ServerSentEvent.<String>builder()
                    .event("progress")
                    .data(JSONUtil.toJsonStr(Map.of("d", message)))
                    .build());
        }
    }

    public void complete(Long appId)
    {
        Sinks.Many<ServerSentEvent<String>> sink = sinks.remove(appId);
        if (sink != null)
        {
            sink.tryEmitComplete();
        }
    }
}
