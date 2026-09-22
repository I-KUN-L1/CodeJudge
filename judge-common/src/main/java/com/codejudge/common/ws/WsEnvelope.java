package com.codejudge.common.ws;

import lombok.Data;

import java.io.Serializable;

/**
 * WebSocket 统一消息信封。
 *
 * <p>所有推送（判题进度、榜单变更、竞赛状态、心跳）都套这一层，客户端先读
 * {@link #type} 再决定如何解析 {@link #data}，避免每类消息各写一套协议。
 *
 * <p><b>seq 的用途</b>：同一 {@link #topic} 上的单调递增序号。推送合并（见各服务 Pusher）
 * 会主动丢弃中间态，客户端据此可以：
 * <ul>
 *   <li>检测丢包/乱序（seq 跳号）→ 走一次 REST 兜底查询；</li>
 *   <li>丢弃过期消息（seq 退回）。</li>
 * </ul>
 *
 * <p><b>full 的用途</b>：榜单的「内部全量视图」标记。封榜期间对外只发
 * {@code full=false} 的冻结榜，教师/管理员订阅的会话收到 {@code full=true} 的实时榜。
 */
@Data
public class WsEnvelope implements Serializable {

    /** 消息类型（{@link WsMessageType} 名称） */
    private String type;

    /** 订阅主题（服务端路由键，例如 {@code sub:123456} / {@code contest:5002:rank:public}） */
    private String topic;

    /** 同主题单调递增序号，从 1 开始 */
    private long seq;

    /** 是否为全量视图（封榜期间 true 仅下发给教师/管理员会话） */
    private boolean full;

    /** 服务端时间戳（毫秒） */
    private long ts;

    /** 业务负载（结构随 type 变化） */
    private Object data;

    public static WsEnvelope of(WsMessageType type, String topic, Object data) {
        WsEnvelope e = new WsEnvelope();
        e.type = type.name();
        e.topic = topic;
        e.ts = System.currentTimeMillis();
        e.data = data;
        return e;
    }

    public static WsEnvelope error(String topic, String message) {
        return of(WsMessageType.ERROR, topic, java.util.Map.of("message", message));
    }
}
