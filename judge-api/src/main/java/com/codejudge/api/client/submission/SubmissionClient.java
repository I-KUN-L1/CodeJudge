package com.codejudge.api.client.submission;

import com.codejudge.api.dto.contest.ContestSubmissionDTO;
import com.codejudge.api.dto.submission.SubmissionReviewContextDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 提交服务客户端（内部契约）。
 *
 * <p>消费方：
 * <ul>
 *   <li>judge-contest —— 终榜重建：榜单是 Redis 上的派生数据，一旦 Redis 被清空
 *       或排行逻辑升级（例如罚时规则变更），必须能从**权威数据源**重算。
 *       权威数据源是 judge_submission 库的提交表，因此走内部 Feign 而不是跨库直读
 *       —— 跨库直读会把两个服务的表结构耦合成一个（改表即破坏他人），是典型的边界反模式。</li>
 *   <li>judge-ai —— AI 点评的输入侧：拉取代码 + 判题结论 + 逐用例结果。</li>
 * </ul>
 */
@FeignClient(value = "judge-submission", contextId = "submissionClient")
public interface SubmissionClient {

    /**
     * 拉取某竞赛下的全部终态提交（按提交时间升序，供按序回放）。
     *
     * @param contestId 竞赛 id
     * @param limit     条数上限（防止一次请求打爆内存）
     */
    @GetMapping("/internal/submissions/contest/{contestId}/results")
    List<ContestSubmissionDTO> listContestResults(@PathVariable("contestId") Long contestId,
                                                  @RequestParam(value = "limit", required = false) Integer limit);

    /**
     * 拉取 AI 点评所需的提交上下文（代码 + 判题结论 + 逐用例样本 + 编译信息）。
     *
     * @param id         提交 id
     * @param maskHidden 是否遮蔽隐藏用例的输出摘要。
     *                   <b>学员触发点评时必须传 true</b>（默认值即为 true），
     *                   否则学员可借 AI 之口问出隐藏用例的期望输出，绕过 P3 的 oracle 防护；
     *                   教师/员工可传 false 以获得完整诊断信息。
     */
    @GetMapping("/internal/submissions/{id}/review-context")
    SubmissionReviewContextDTO getReviewContext(@PathVariable("id") Long id,
                                                @RequestParam(value = "maskHidden", required = false, defaultValue = "true")
                                                boolean maskHidden);
}
