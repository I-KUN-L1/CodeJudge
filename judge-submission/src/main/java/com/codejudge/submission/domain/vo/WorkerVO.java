package com.codejudge.submission.domain.vo;

import lombok.Data;

/**
 * 判题机在线状态（/workers 视图项，管理员专用）。
 */
@Data
public class WorkerVO {

    /** workerId（host:port 派生） */
    private String workerId;

    /** 心跳内容（host:port） */
    private String heartbeat;

    /** 在跑任务数（来自负载 ZSet score） */
    private Long runningTasks;
}
