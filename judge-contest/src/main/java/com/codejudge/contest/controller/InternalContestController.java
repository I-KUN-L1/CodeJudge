package com.codejudge.contest.controller;

import com.codejudge.api.dto.contest.ContestContextDTO;
import com.codejudge.common.annotation.NoWrapper;
import com.codejudge.common.utils.InternalOnlyGuard;
import com.codejudge.contest.service.ContestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 竞赛内部接口（仅服务间 Feign 直连可用）。
 *
 * <p>消费方：judge-submission（受理竞赛提交前校验「竞赛进行中 / 题目属于该竞赛 / 用户已报名」）。
 * 端点以 {@link InternalOnlyGuard} 保护：网关透传的任何已登录用户（含管理员）访问一律 403。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/contests")
@Tag(name = "竞赛内部接口", description = "仅限服务间 Feign 调用（InternalOnlyGuard 保护）")
public class InternalContestController {

    private final ContestService contestService;

    @Operation(summary = "竞赛上下文（内部）", description = "供 judge-submission 校验竞赛提交合法性；状态按当前时间实时推导")
    @GetMapping("/{id}/context")
    @NoWrapper
    public ContestContextDTO context(@PathVariable("id") Long id,
                                     @RequestParam(value = "userId", required = false) Long userId) {
        InternalOnlyGuard.checkInternal();
        return contestService.context(id, userId);
    }
}
