package com.codejudge.api.client.problem;

import com.codejudge.api.dto.problem.JudgeInfoDTO;
import com.codejudge.api.dto.problem.ProblemSummaryDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 题目服务客户端（内部契约）。
 *
 * <p>仅供 judge-submission / judge-worker 经 Feign 直连 judge-problem 消费，
 * 对应端点受 {@code InternalOnlyGuard} 保护 —— 携带用户身份的外部请求一律 403。
 *
 * <p>有意不配 fallback：判题链路对「题目信息缺失」的正确姿势是失败并重试，
 * 而不是拿降级空数据继续跑（用例为空会把所有提交误判成 AC）。
 * 调用方捕获 Feign 异常后走 judge_task 的重试/死信机制。
 */
@FeignClient(value = "judge-problem", contextId = "problemClient")
public interface ProblemClient {

    /**
     * 拉取判题所需题目信息（限制 + 全部用例，含隐藏用例）。
     * 题目不存在时服务端抛业务异常（RDecoder 解包后转为 BizIllegalException）。
     */
    @GetMapping("/internal/problems/{id}/judge-info")
    JudgeInfoDTO getJudgeInfo(@PathVariable("id") Long id);

    /**
     * 批量拉取题目摘要（不含用例）——仅供竞赛编排校验与榜面展示。
     *
     * <p>与 {@link #getJudgeInfo} 分成两个端点，是为了避免「校验一个 id 却传输全部隐藏用例」
     * 的数据暴露面：题目答案只应流向判题链路。
     */
    @GetMapping("/internal/problems/summaries")
    List<ProblemSummaryDTO> listSummaries(@RequestParam("ids") List<Long> ids);
}
