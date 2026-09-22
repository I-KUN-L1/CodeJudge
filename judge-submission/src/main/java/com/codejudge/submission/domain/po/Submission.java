package com.codejudge.submission.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 提交记录。
 *
 * <p>状态机：{@code PENDING → JUDGING → SUCCESS | FAILED}
 * <ul>
 *   <li>PENDING：已受理，判题任务在队列中；</li>
 *   <li>JUDGING：worker 已认领（租约生效）；</li>
 *   <li>SUCCESS：判题完成（verdict 为 AC/WA/TLE/MLE/RE/CE —— CE 也是"成功的判题"）；</li>
 *   <li>FAILED：平台侧失败（verdict=SE），超过最大重试转 DEAD 任务时置入。</li>
 * </ul>
 * 终态写入均带 {@code WHERE status IN ('PENDING','JUDGING')} 条件，防止迟到的旧结果
 * 覆盖重判后的新状态（状态机只允许前进，不允许回退）。
 *
 * <p>幂等：唯一索引 {@code uk_submission_idempotent(user_id, problem_id, contest_id, code_hash, submit_round)}。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("submission")
public class Submission extends BasePO {

    /** 提交人 */
    private Long userId;

    /** 题目 id */
    private Long problemId;

    /** 竞赛 id，0=非竞赛提交 */
    private Long contestId;

    /** 语言：JAVA/PYTHON/CPP/GO */
    private String language;

    /** 代码全文（≤32KB 存此列；超长转 MinIO 的能力 P3 预留 code_path，未启用） */
    private String code;

    /** 超长代码对象存储路径（预留） */
    private String codePath;

    /** 代码 SHA-256，参与提交幂等 */
    private String codeHash;

    /** 同题同码重复提交轮次，参与提交幂等（0 起） */
    private Integer submitRound;

    /** 状态：PENDING/JUDGING/SUCCESS/FAILED */
    private String status;

    /** 判题结论：AC/WA/TLE/MLE/RE/CE/SE */
    private String verdict;

    /** 得分（AC 用例分值之和） */
    private Integer score;

    /** 全部用例最大耗时(ms) */
    private Integer timeMs;

    /** 全部用例最大内存(KB) */
    private Integer memoryKb;

    /** 编译信息 id */
    private Long compileInfoId;

    /** 提交时间 */
    private LocalDateTime submitTime;

    /** 关联判题任务 id（仅事务内组装 VO / MQ 消息用，不落库） */
    @TableField(exist = false)
    private Long taskId;
}
