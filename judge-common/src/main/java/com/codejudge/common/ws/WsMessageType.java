package com.codejudge.common.ws;

/**
 * 服务端 → 客户端的 WebSocket 消息类型。
 *
 * <p>约定：客户端**只消费**本枚举声明的类型，未知类型一律忽略（便于服务端灰度新增类型）。
 * 客户端 → 服务端只有两条控制消息：{@code SUBSCRIBE}/{@code PING}，见
 * {@code com.codejudge.common.ws.WsControlType}（各服务自行解析，不做强制抽象）。
 */
public enum WsMessageType {

    /** 握手成功：携带订阅主题、服务端实例标识与当前主题序号 */
    CONNECTED,

    /**
     * 连接即推的快照。
     *
     * <p>存在意义：WebSocket 只推「变化」，而客户端可能在中途才连上 —— 若没有快照，
     * 连接前已发生的进度/榜单变化会永久丢失（客户端只能再发一次 REST 查询）。
     */
    SNAPSHOT,

    /** 判题进度（PENDING/JUDGING/单用例完成） */
    SUB_PROGRESS,

    /** 判题终态（AC/WA/TLE/MLE/RE/CE/SE） */
    SUB_RESULT,

    /** 榜单变更（携带榜单视图与版本号） */
    RANK_UPDATE,

    /** 竞赛状态变更（开赛 / 封榜 / 结束）—— 封榜切换必须主动通知，否则客户端无法知道该换读哪个榜 */
    CONTEST_STATUS,

    /** 服务端心跳（客户端可选回 PONG） */
    PING,

    /** 心跳响应 */
    PONG,

    /** 错误（鉴权失败、订阅目标不存在、参数非法）—— 发送后服务端主动关闭连接 */
    ERROR
}
