package com.codejudge.worker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codejudge.worker.domain.po.JudgeTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface WorkerJudgeTaskMapper extends BaseMapper<JudgeTask> {

    /**
     * 认领任务（幂等核心，与 judge-submission 侧语义一致）：
     * CAS 条件 status='PENDING' AND attempt=#{attempt} —— at-least-once 重复投递下
     * 只有一次认领生效；已被其他 worker 抢走或消息过期时返回 0。
     *
     * <p>实现注意：MySQL PreparedStatement 中 {@code INTERVAL ? MILLISECOND} 非法
     * （实测踩坑 2026-09-20），租约到期时间在 Java 侧算好作为参数传入。
     *
     * @return 1=认领成功（租约生效）
     */
    @Update("""
            UPDATE judge_task
            SET status = 'JUDGING',
                worker_id = #{workerId},
                lease_owner = #{workerId},
                lease_expire_at = #{leaseExpireAt},
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = 'PENDING'
              AND attempt = #{attempt}
              AND deleted = 0
            """)
    int claim(@Param("taskId") Long taskId,
              @Param("attempt") int attempt,
              @Param("workerId") String workerId,
              @Param("leaseExpireAt") java.time.LocalDateTime leaseExpireAt);

    /**
     * 本 worker 标记任务终态：CAS 条件含 lease_owner=#{workerId}，
     * 保证只有租约持有者能写终态（防接管后的旧执行覆盖新执行）。
     */
    @Update("""
            UPDATE judge_task
            SET status = 'SUCCESS', update_time = NOW()
            WHERE id = #{taskId}
              AND status = 'JUDGING'
              AND lease_owner = #{workerId}
              AND deleted = 0
            """)
    int markSuccess(@Param("taskId") Long taskId, @Param("workerId") String workerId);

    /**
     * 本 worker 交还任务进入重试：attempt+1、清租约、置 next_retry_at。
     * CAS 条件同上，防止与故障转移接管并发写。
     */
    @Update("""
            UPDATE judge_task
            SET status = 'PENDING',
                worker_id = NULL,
                lease_owner = NULL,
                lease_expire_at = NULL,
                attempt = attempt + 1,
                next_retry_at = NOW(),
                error_msg = #{reason},
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = 'JUDGING'
              AND lease_owner = #{workerId}
              AND deleted = 0
            """)
    int handBackForRetry(@Param("taskId") Long taskId, @Param("workerId") String workerId,
                         @Param("reason") String reason);

    /** 本 worker 标记死信终态（attempt 已超限）：CAS 同上 */
    @Update("""
            UPDATE judge_task
            SET status = 'DEAD',
                error_msg = #{reason},
                update_time = NOW()
            WHERE id = #{taskId}
              AND status = 'JUDGING'
              AND lease_owner = #{workerId}
              AND deleted = 0
            """)
    int markDead(@Param("taskId") Long taskId, @Param("workerId") String workerId,
                 @Param("reason") String reason);
}
