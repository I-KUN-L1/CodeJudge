package com.codejudge.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codejudge.common.constants.JudgeRedisKeys;
import com.codejudge.common.exceptions.ForbiddenException;
import com.codejudge.common.utils.UserContext;
import com.codejudge.submission.domain.po.JudgeTask;
import com.codejudge.submission.domain.vo.WorkerVO;
import com.codejudge.submission.mapper.JudgeTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 判题机集群视图（/workers，管理员专用）。
 *
 * <p>数据源全部为 Redis 心跳/负载 + judge_task 聚合：
 * <ul>
 *   <li>在线 worker：scan {@code judge:worker:heartbeat:*}（TTL 30s，过期自动消失 = 离线）；</li>
 *   <li>负载：ZSet {@code judge:worker:load}，score = 在跑任务数；</li>
 *   <li>队列积压：ZCard {@code judge:judge:queue:zset}；</li>
 *   <li>死信积压：judge_task 中 status=DEAD 计数。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerViewService {

    private final StringRedisTemplate redis;
    private final JudgeTaskMapper judgeTaskMapper;

    /** 在线 worker 列表（心跳未过期即在线） */
    public List<WorkerVO> listOnlineWorkers() {
        requireAdmin();
        List<WorkerVO> workers = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(JudgeRedisKeys.WORKER_HEARTBEAT_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String workerId = key.substring(JudgeRedisKeys.WORKER_HEARTBEAT_PREFIX.length());
                WorkerVO vo = new WorkerVO();
                vo.setWorkerId(workerId);
                vo.setHeartbeat(redis.opsForValue().get(key));
                Double load = redis.opsForZSet().score(JudgeRedisKeys.WORKER_LOAD_ZSET, workerId);
                vo.setRunningTasks(load == null ? 0 : load.longValue());
                workers.add(vo);
            }
        }
        return workers;
    }

    /** 待判队列积压（ZSet member 数） */
    public Long queueBacklog() {
        requireAdmin();
        return queueBacklogRaw();
    }

    /** 死信积压（DEAD 任务数） */
    public Long deadTaskCount() {
        requireAdmin();
        return deadTaskCountRaw();
    }

    // ==========================================================================
    // 无鉴权版本（供 Micrometer 指标采集使用）
    //
    // 为什么必须拆出来：Prometheus 抓取 /actuator/prometheus 时**没有登录用户**，
    // UserContext 恒为空 → 上面的 requireAdmin() 会直接抛 ForbiddenException，
    // 导致整个 scrape 失败（而失败表现只是"指标忽有忽无"，极难定位）。
    //
    // 安全性：这三个方法只读、不返回任何用户数据，仅暴露聚合计数；
    // 且只被进程内的 MeterBinder 调用，不经过 Controller —— 没有外部可达路径。
    // ==========================================================================

    /**
     * 待判队列积压（无鉴权）。
     *
     * <p>口径依赖 {@code JudgeCompensationService#queueReconcileScan} 的队列对账：
     * 该扫描负责摘除「对应任务已终态」的僵尸成员。若对账失效，本指标会只增不减 ——
     * 系统空闲时也报有积压，积压告警随之失效（P6 实测复现过：2 个 DEAD 任务让积压恒为 2）。
     */
    public Long queueBacklogRaw() {
        Long size = redis.opsForZSet().zCard(JudgeRedisKeys.JUDGE_QUEUE_ZSET);
        return size == null ? 0 : size;
    }

    /** 死信积压（无鉴权） */
    public Long deadTaskCountRaw() {
        return judgeTaskMapper.selectCount(new LambdaQueryWrapper<JudgeTask>()
                .eq(JudgeTask::getStatus, "DEAD"));
    }

    /** 在线判题机数量（无鉴权）。心跳 key 带 30s TTL，过期即消失，所以 SCAN 数量即在线数 */
    public long onlineWorkerCountRaw() {
        long count = 0;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(JudgeRedisKeys.WORKER_HEARTBEAT_PREFIX + "*").count(100).build())) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return count;
    }

    private void requireAdmin() {
        Integer role = UserContext.getRole();
        if (role == null || role != 1) {
            throw new ForbiddenException("仅管理员可查看判题机集群状态");
        }
    }
}
