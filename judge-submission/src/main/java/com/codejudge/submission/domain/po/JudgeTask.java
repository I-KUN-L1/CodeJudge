package com.codejudge.submission.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 判题任务（生命周期与故障转移的核心载体）。
 *
 * <p>状态机：{@code PENDING → JUDGING → SUCCESS | FAILED | DEAD}
 * <ul>
 *   <li>PENDING：待领取。worker 以 CAS（WHERE status='PENDING' AND attempt=#{attempt}）
 *       认领，保证 at-least-once 重复投递下只有一次执行生效；</li>
 *   <li>JUDGING：租约生效中。{@link #leaseOwner} 持有，{@link #leaseExpireAt} 到期即视为
 *       worker 失联 → 补偿任务接管（故障转移）；</li>
 *   <li>SUCCESS：判题完成（含 CE —— 编译失败是"成功的判题"）；</li>
 *   <li>FAILED：本次尝试失败、仍会重试（attempt &lt; max_attempt）；</li>
 *   <li>DEAD：超过最大重试，同时投递业务死信 topic（judge_submission_dlq）。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("judge_task")
public class JudgeTask extends BasePO {

    /** 提交 id */
    private Long submissionId;

    /** 处理中的判题机 id */
    private String workerId;

    /** 状态：PENDING/JUDGING/SUCCESS/FAILED/DEAD */
    private String status;

    /** 已执行尝试次数（从 0 开始） */
    private Integer attempt;

    /** 最大重试次数 */
    private Integer maxAttempt;

    /** 任务级超时（含编译），同时是租约时长 */
    private Integer timeoutMs;

    /** 租约持有者 workerId */
    private String leaseOwner;

    /** 租约到期时间（到期即可被补偿任务接管 → 故障转移） */
    private LocalDateTime leaseExpireAt;

    /** 下次可重试时间 */
    private LocalDateTime nextRetryAt;

    /** 最近一次失败原因 */
    private String errorMsg;
}
