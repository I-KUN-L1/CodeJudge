package com.codejudge.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codejudge.submission.domain.po.JudgeTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface JudgeTaskMapper extends BaseMapper<JudgeTask> {

    /**
     * worker 认领任务（幂等核心）。
     *
     * <p>CAS 条件：status='PENDING' 且 attempt 与消息一致 —— 同一消息重复投递
     * （at-least-once）时只有第一次认领成功（受影响行数=1），其余返回 0 被丢弃。
     * 用数据库行级原子性替代分布式锁（judge-common 已移除 Redisson 的决策依据之一）。
     *
     * <p>实现注意：MySQL PreparedStatement 中 {@code INTERVAL ? MILLISECOND} 非法
     * （实测踩坑 2026-09-20），租约到期时间由调用方在 Java 侧算好传入。
     *
     * @return 1=认领成功；0=已被其他 worker 认领或已非该轮次
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
     * 故障转移接管：租约已过期的 JUDGING 任务复位为 PENDING，attempt+1 交给下一轮。
     * CAS 条件（status + lease_expire_at < NOW()）保证多个调度实例并发扫描时只有一次生效。
     *
     * @return 1=接管成功；0=租约已被续期或任务已被处理
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
              AND lease_expire_at < NOW()
              AND deleted = 0
            """)
    int takeoverExpiredLease(@Param("taskId") Long taskId, @Param("reason") String reason);
}
