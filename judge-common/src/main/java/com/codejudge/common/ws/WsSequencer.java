package com.codejudge.common.ws;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主题级单调序号发生器。
 *
 * <p>推送合并（Pusher）会主动丢弃中间态，客户端需要一个可比较的序号来判断
 * 「我看到的榜单是不是最新的」「中间是否错过了消息」。序号在**本实例内**单调，
 * 跨实例不保证全局有序（那需要引入集中式序列，成本远高于收益）：
 * 序号的作用域是「同一条连接」，而一条连接只属于一个实例，因此足够。
 */
public class WsSequencer {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 取下一个序号（从 1 开始） */
    public long next(String topic) {
        return counters.computeIfAbsent(topic, k -> new AtomicLong()).incrementAndGet();
    }

    /** 当前序号（不递增），用于快照消息对齐 */
    public long current(String topic) {
        AtomicLong counter = counters.get(topic);
        return counter == null ? 0 : counter.get();
    }

    /** 主题生命周期结束（竞赛结束/会话主题下线）时清理，避免长期运行累积 */
    public void forget(String topic) {
        counters.remove(topic);
    }
}
