package com.codejudge.submission.controller;

import com.codejudge.common.annotation.RequireRole;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.domain.R;
import com.codejudge.submission.domain.dto.SubmissionFormDTO;
import com.codejudge.submission.domain.dto.SubmissionQuery;
import com.codejudge.submission.domain.vo.SubmissionDetailVO;
import com.codejudge.submission.domain.vo.SubmissionVO;
import com.codejudge.submission.service.SubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提交接口（网关前缀 /submissions/**）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/submissions")
@Tag(name = "提交管理", description = "代码提交（幂等）、判题结果查询、重判")
public class SubmissionController {

    private final SubmissionService submissionService;

    @Operation(summary = "提交代码", description = "幂等：60s 内同用户同题同码重复提交返回已有记录（idempotent=true）；受理后经 MQ 异步判题")
    @PostMapping
    public R<SubmissionVO> submit(@Valid @RequestBody SubmissionFormDTO form) {
        return R.ok(submissionService.submit(form));
    }

    @Operation(summary = "提交详情", description = "本人/教师/管理员可见；学员视角隐藏用例不下发输出摘要")
    @GetMapping("/{id}")
    public R<SubmissionDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(submissionService.queryDetail(id));
    }

    @Operation(summary = "提交分页", description = "学员仅见本人提交；教师/管理员可按 userId/problemId/status/verdict 过滤")
    @GetMapping("/page")
    public R<PageDTO<SubmissionVO>> page(SubmissionQuery query) {
        return R.ok(submissionService.page(query));
    }

    @Operation(summary = "重判", description = "教师（限本人题目）/管理员：复位状态机、清空历史结果后重新投递判题")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @PostMapping("/{id}/rejudge")
    public R<Void> rejudge(@PathVariable("id") Long id) {
        // 角色校验见方法内（复用 @RequireRole 的语义但在服务层做题目归属判断）
        submissionService.rejudge(id);
        return R.ok();
    }
}
