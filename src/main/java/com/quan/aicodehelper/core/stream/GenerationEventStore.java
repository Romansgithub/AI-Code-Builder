package com.quan.aicodehelper.core.stream;

import com.quan.aicodehelper.config.SseProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成进度事件缓冲：断线期间的事件在重连后仍可见。按 appId 维护有界环形缓冲，
 * 淘汰最旧事件并记录 lostBefore；每个应用一把锁，当前仅在单实例 JVM 内成立。
 */
@Slf4j
@Component
public class GenerationEventStore {

    /** 单应用事件流。nextId 以「放入缓冲的时刻」为准递增，故与缓冲共用一把锁维护。 */
    private static class Stream {
        private final Deque<GenerationEvent> buffer = new ArrayDeque<>();
        private long nextId = 1L;
        /** 前端报告的 lastEventId 小于它时说明事件存在缺口，需明确告知而非假装连续。 */
        private long lostBefore = 1L;
    }

    private final Map<Long, Stream> streams = new ConcurrentHashMap<>();

    @Resource
    private SseProperties sseProperties;

    /** @return null 表示该应用事件流已回收；生成与订阅解耦，没人听不代表生成要失败 */
    public GenerationEvent append(Long appId, String event, String data) {
        if (appId == null) {
            return null;
        }
        Stream stream = streams.computeIfAbsent(appId, k -> new Stream());
        GenerationEvent recorded;
        synchronized (stream) {
            long id = stream.nextId++;
            recorded = GenerationEvent.of(id, event, data);
            stream.buffer.addLast(recorded);
            trim(stream);
        }
        return recorded;
    }

    /**
     * @param lastEventId 前端确认收到的最后一个事件 id；0 表示从头开始
     * @return 未读事件，按 id 升序；应用不存在时返回空列表
     */
    public List<GenerationEvent> since(Long appId, long lastEventId) {
        if (appId == null) {
            return List.of();
        }
        Stream stream = streams.get(appId);
        if (stream == null) {
            return List.of();
        }
        synchronized (stream) {
            List<GenerationEvent> result = new ArrayList<>();
            for (GenerationEvent e : stream.buffer) {
                if (e.id() > lastEventId) {
                    result.add(e);
                }
            }
            return result;
        }
    }

    /** 订阅建立时先取水位，之后只下发 id 大于水位的实时事件，避免同一事件既补发又推送。 */
    public long currentMaxId(Long appId) {
        Stream stream = appId == null ? null : streams.get(appId);
        if (stream == null) {
            return 0L;
        }
        synchronized (stream) {
            return stream.nextId - 1;
        }
    }

    public long lostBefore(Long appId) {
        Stream stream = appId == null ? null : streams.get(appId);
        if (stream == null) {
            return 0L;
        }
        synchronized (stream) {
            return stream.lostBefore;
        }
    }

    public List<GenerationEvent> snapshot(Long appId) {
        return since(appId, 0L);
    }

    /** 回收时机是「任务保留期到期」而非「任务结束」：刚结束时用户可能正在重连，缓冲不能扔。 */
    public void evict(Long appId) {
        if (appId != null) {
            streams.remove(appId);
        }
    }

    public int trackedCount() {
        return streams.size();
    }

    private void trim(Stream stream) {
        int limit = sseProperties.getBufferSize();
        while (stream.buffer.size() > limit) {
            GenerationEvent dropped = stream.buffer.pollFirst();
            if (dropped == null) {
                break;
            }
            stream.lostBefore = dropped.id() + 1;
            log.debug("应用事件缓冲超出上限（{}），淘汰事件 id={}", limit, dropped.id());
        }
    }

    /** 只按时间裁旧事件；应用条目本身的回收交给任务状态管理（任务还在跑就不能清理）。 */
    public void purgeExpired(long expireBefore) {
        Iterator<Map.Entry<Long, Stream>> it = streams.entrySet().iterator();
        while (it.hasNext()) {
            Stream stream = it.next().getValue();
            synchronized (stream) {
                while (!stream.buffer.isEmpty()
                        && stream.buffer.peekFirst().created() < expireBefore) {
                    GenerationEvent dropped = stream.buffer.pollFirst();
                    if (dropped != null) {
                        stream.lostBefore = dropped.id() + 1;
                    }
                }
            }
        }
    }
}
