package com.codejudge.submission.controller;

import com.codejudge.api.dto.contest.ContestSubmissionDTO;
import com.codejudge.api.dto.submission.SubmissionReviewContextDTO;
import com.codejudge.common.annotation.NoWrapper;
import com.codejudge.common.utils.InternalOnlyGuard;
import com.codejudge.submission.service.SubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提交内部接口（仅服务间 Feign 直连可用）。
 *
 * <p>消费方：judge-contest 的终榜重建（榜单是 Redis 派生数据，权威数据在提交表）。
 * 端点以 {@link InternalOnlyGuard} 保护；此外网关**没有** {@code /internal/**} 路由，
 * 外部流量在网关层就会被 404 掉 —— 两道防线。
 *
 * <p>数据面：只返回重建榜单必需的列，**不含代码、不含用例输出**。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/submissions")
@Tag(name = "提交内部接口", description = "仅限服务间 Feign 调用（InternalOnlyGuard 保护）")
public class InternalSubmissionController {

    private final SubmissionService submissionService;

    @Operation(summary = "竞赛终态提交列表（内部）", description = "供 judge-contest 重建榜单；按提交时间升序，不含代码与用例输出")
    @GetMapping("/contest/{contestId}/results")
    @NoWrapper
    public List<ContestSubmissionDTO> contestResults(@PathVariable("contestId") Long contestId,
                                                     @RequestParam(value = "limit", required = false) Integer limit) {
        InternalOnlyGuard.checkInternal();
        return submissionService.listContestResults(contestId, limit);
    }

    @Operation(summary = "AI 点评上下文（内部）",
            description = "供 judge-ai 组装点评 Prompt；含代码、判题结论与逐用例样本。"
                    + "maskHidden=true（默认）时遮蔽隐藏用例的输出摘要 —— 学员触发的点评必须走这条，"
                    + "否则可借 AI 之口问出隐藏用例期望输出，绕过判题隔离")
    @GetMapping("/{id}/review-context")
    @NoWrapper
    public SubmissionReviewContextDTO reviewContext(
            @PathVariable("id") Long id,
            @RequestParam(value = "maskHidden", required = false, defaultValue = "true") boolean maskHidden) {
        InternalOnlyGuard.checkInternal();
        return submissionService.reviewContext(id, maskHidden);
    }
}
