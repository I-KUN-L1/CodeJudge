package com.codejudge.submission.controller;

import com.codejudge.common.domain.R;
import com.codejudge.submission.domain.vo.WorkerVO;
import com.codejudge.submission.service.WorkerViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 判题机集群视图（/workers/**，管理员专用）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/workers")
@Tag(name = "判题机集群", description = "在线 worker / 队列积压 / 死信积压（管理员）")
public class WorkerController {

    private final WorkerViewService workerViewService;

    @Operation(summary = "在线判题机", description = "来自 Redis 心跳（TTL 30s，过期即离线）与负载 ZSet")
    @GetMapping
    public R<List<WorkerVO>> workers() {
        return R.ok(workerViewService.listOnlineWorkers());
    }

    @Operation(summary = "集群指标", description = "队列积压（待判 ZSet member 数）与死信积压（DEAD 任务数）")
    @GetMapping("/metrics")
    public R<Map<String, Long>> metrics() {
        return R.ok(Map.of(
                "queueBacklog", workerViewService.queueBacklog(),
                "deadTasks", workerViewService.deadTaskCount()
        ));
    }
}
