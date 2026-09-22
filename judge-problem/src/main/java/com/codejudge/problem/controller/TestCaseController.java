package com.codejudge.problem.controller;

import com.codejudge.common.annotation.RequireRole;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.R;
import com.codejudge.problem.domain.dto.TestCaseFormDTO;
import com.codejudge.problem.service.TestCaseService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试用例（单个用例的读写）
 *
 * <p>接口划分：**集合级**操作（列出全部 / 批量新增 / 全量替换）是题目的子资源，
 * 挂在 {@code /problems/{id}/test-cases}（见 {@link ProblemController}）；
 * **单条级**操作在此处按用例 id 寻址 {@code /test-cases/{caseId}}。
 * 两者都在网关已声明的 {@code /problems/**} 与 {@code /test-cases/**} 路由内。
 *
 * <p>归属校验不在此层展开：用例→题目→ownerId 的链路校验统一在 Service 内完成，
 * Controller 只负责角色门槛（教师/管理员）。
 */
@RestController
@RequestMapping("/test-cases")
@RequiredArgsConstructor
public class TestCaseController {

    private final TestCaseService testCaseService;

    @PutMapping("/{caseId}")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "修改单个用例（仅覆盖非空字段）")
    public R<Void> updateCase(@PathVariable Long caseId, @RequestBody TestCaseFormDTO form) {
        testCaseService.updateCase(caseId, form);
        return R.ok();
    }

    @DeleteMapping("/{caseId}")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "删除单个用例（物理删除，序号可被复用）")
    public R<Void> deleteCase(@PathVariable Long caseId) {
        testCaseService.deleteCase(caseId);
        return R.ok();
    }
}
