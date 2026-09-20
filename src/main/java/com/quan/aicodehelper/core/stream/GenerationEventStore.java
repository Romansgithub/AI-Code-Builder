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
 * 生成进度事件缓冲：让「断线期间发生的事」在重连后仍然可见。
 *
 * <p><b>它解决什么问题</b>：进度事件原本是「有人听着才存在」的瞬时信号 ——
 * 连接断开期间 {@code emit} 的事件直接丢弃，用户重连后进度条停在断线那一刻，
 * 中间发生的事（写了哪些文件、修复到第几轮）永久丢失，
 * 表现为「一直在转圈但什么都不发生」。</p>
 *
 * <p><b>怎么做</b>：按 appId 维护一个<b>有界环形缓冲</b>。事件一旦产生就落进缓冲，
 * 无论当时有没有订阅者；订阅者只决定「什么时候被叫醒」，不再决定「事件是否存在」。
 * 这样重连时只需带上最后收到的 id，就能把落后的部分补齐。</p>
 *
 * <p><b>为什么有界</b>：用户可能开着页面再也不回来，无界缓冲会让内存随应用数量无限增长。
 * 超出上限时淘汰最旧的事件 —— 丢掉最早的进度是可接受的降级，
 * 但必须记账（见 {@link #lostBefore}），否则前端会看到 id 缺口却无从解释。</p>
 *
 * <p><b>线程安全</b>：读（订阅、补发）与写（生成线程 emit）在不同线程上并发发生。
 * 每个应用一把锁，而不是一把全局锁：不同应用的生成互不影响，
 * 避免一个慢应用的事件推送卡住整站。</p>
 *
 * <p><b>当前边界</b>：缓冲在 JVM 内存中，因此只在单实例部署下成立。
 * 多实例时需要把缓冲换成 Redis Pub/Sub + Stream，本类即是替换点。</p>
 */
@Slf4j
@Component
public class GenerationEventStore {

    /**
     * 事件序号分配器与已消费水位。
     *
     * <p>{@code nextId} 必须以「放入缓冲的时刻」为准递增，因此和缓冲放在同一把锁下维护。</p>
     */
    private static class Stream {
        private final Deque<GenerationEvent> buffer = new ArrayDeque<>();
        private long nextId = 1L;
        /**
         * 已经被淘汰的最小 id。前端若报告自己的 lastEventId 小于它，
         * 说明中间的事件已经丢了，需要明确告知而不是假装连续。
         */
        private long lostBefore = 1L;
    }

    private final Map<Long, Stream> streams = new ConcurrentHashMap<>();

    @Resource
    private SseProperties sseProperties;

    /**
     * 记录一条事件并返回它。
     *
     * <p>返回 {@code null} 表示该应用的事件流已被回收（如任务保留期已过），
     * 调用方无需特殊处理 —— 生成与订阅是解耦的，没人听不代表生成要失败。</p>
     */
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
     * 读取 {@code lastEventId} 之后的所有事件。
     *
     * @param lastEventId 前端确认收到的最后一个事件 id；0 表示从头开始（首次连接）
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

    /**
     * 该应用当前已分配到的最大 id。
     *
     * <p>用于「实时下发 + 事后补发」的衔接：订阅建立那一刻先问一次水位，
     * 之后只对 id 大于水位的实时事件做下发，避免同一条事件既被补发又被推送。</p>
     */
    public long currentMaxId(Long appId) {
        Stream stream = appId == null ? null : streams.get(appId);
        if (stream == null) {
            return 0L;
        }
        synchronized (stream) {
            return stream.nextId - 1;
        }
    }

    /**
     * 已被淘汰的最小 id。前端报告的 lastEventId 小于它时，说明存在事件缺口。
     */
    public long lostBefore(Long appId) {
        Stream stream = appId == null ? null : streams.get(appId);
        if (stream == null) {
            return 0L;
        }
        synchronized (stream) {
            return stream.lostBefore;
        }
    }

    /**
     * 读取缓冲中当前保留的全部事件（用于测试与问题排查）。
     */
    public List<GenerationEvent> snapshot(Long appId) {
        return since(appId, 0L);
    }

    /**
     * 回收一个应用的事件流。
     *
     * <p>调用时机是「任务保留期到期」，而不是「任务结束」——
     * 任务刚结束时用户很可能正在重连，缓冲还不能扔。</p>
     */
    public void evict(Long appId) {
        if (appId != null) {
            streams.remove(appId);
        }
    }

    /**
     * 当前仍在缓冲中的应用数量，用于观测内存规模。
     */
    public int trackedCount() {
        return streams.size();
    }

    /**
     * 环形淘汰：超出上限时丢最旧的，并推进 {@code lostBefore}。
     */
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

    /**
     * 清理所有缓冲中早于 {@code expireBefore} 的事件。
     *
     * <p>由定时任务调用。这里只按时间裁掉旧事件，应用条目本身的回收
     * 交给任务状态管理 —— 一个应用即便没有订阅者，只要任务还在跑就不能被清理。</p>
     */
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
