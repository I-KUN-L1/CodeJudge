package com.codejudge.submission.domain.po;

import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 代码草稿：答案已填写完毕但**尚未提交**的代码，按（用户, 题目）一题一份。
 *
 * <p>与 {@link Submission} 的本质区别：submission 行 = 一次真实提交（进入判题状态机、
 * 参与幂等去重与榜单统计）；code_draft 行只是编辑器内容的持久化，不参与判题，
 * 也不占用提交幂等键 —— 因此演示账户可以"预填标准答案"，点提交时走的仍是完整
 * 真实判题链路（MQ → worker → 逐用例判题），不会撞 {@code uk_submission_idempotent}。</p>
 *
 * <p>生命周期：提交成功后由前端调用 DELETE 消费掉（草稿已转化为正式提交）。
 * 删除是<b>物理删除</b>而非逻辑删除：草稿没有审计价值，且唯一键
 * {@code uk_code_draft_user_problem(user_id, problem_id)} 会被残留的逻辑删除行挡住，
 * 导致种子脚本/恢复脚本无法重新灌入同题草稿。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("code_draft")
public class CodeDraft extends BasePO {

    /** 归属用户（草稿只对本人可见/可改） */
    private Long userId;

    /** 题目 id */
    private Long problemId;

    /** 语言：JAVA/PYTHON/CPP/GO（与提交接口的枚举一致） */
    private String language;

    /** 代码全文（≤32KB，与 submission.code 同限） */
    private String code;
}
