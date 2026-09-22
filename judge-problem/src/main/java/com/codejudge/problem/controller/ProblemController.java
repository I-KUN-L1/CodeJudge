package com.codejudge.problem.controller;

import com.codejudge.common.annotation.RequireRole;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.domain.R;
import com.codejudge.problem.domain.dto.ProblemFormDTO;
import com.codejudge.problem.domain.dto.ProblemQuery;
import com.codejudge.problem.domain.dto.TestCaseFormDTO;
import com.codejudge.problem.domain.vo.ProblemDetailVO;
import com.codejudge.problem.domain.vo.ProblemVO;
import com.codejudge.problem.domain.vo.ProblemVersionVO;
import com.codejudge.problem.domain.vo.TestCaseVO;
import com.codejudge.problem.service.ProblemService;
import com.codejudge.problem.service.TestCaseService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题目管理
 *
 * <p>路径与角色约定（网关 {@code /problems/**} 已路由到本服务 9083）：
 * <ul>
 *   <li>读接口仅要求登录（网关 JWT 过滤兜底），可见范围由 {@code ProblemService} 按角色收敛；</li>
 *   <li>写接口一律 {@code @RequireRole(STAFF, TEACHER)}，并在 Service 内再过一次归属校验 ——
 *       角色只回答"你能不能建题"，归属才回答"这道题是不是你的"，两者缺一都是越权。</li>
 * </ul>
 */
@RestController
@RequestMapping("/problems")
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemService problemService;
    private final TestCaseService testCaseService;

    // ==================== 写：题目 ====================

    @PostMapping
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "建题（含题面 v1 与模板代码）")
    public R<Long> createProblem(@RequestBody ProblemFormDTO form) {
        return R.ok(problemService.createProblem(form));
    }

    @PutMapping("/{id}")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "改题（题面变更时自动生成新版本）")
    public R<Void> updateProblem(@PathVariable Long id, @RequestBody ProblemFormDTO form) {
        problemService.updateProblem(id, form);
        return R.ok();
    }

    @PutMapping("/{id}/status/{status}")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "变更题目状态（0草稿/1已发布/2已下线）")
    public R<Void> updateStatus(@PathVariable Long id, @PathVariable Integer status) {
        problemService.updateStatus(id, status);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "删除题目（连带物理删除用例与标签关联）")
    public R<Void> deleteProblem(@PathVariable Long id) {
        problemService.deleteProblem(id);
        return R.ok();
    }

    // ==================== 读：题目 ====================

    @GetMapping("/page")
    @Operation(summary = "题目分页（学员仅见已发布；隐藏用例不下发）")
    public R<PageDTO<ProblemVO>> page(ProblemQuery query) {
        return R.ok(problemService.pageQuery(query));
    }

    @GetMapping("/{id}")
    @Operation(summary = "题目详情（隐藏用例仅对归属教师/管理员下发）")
    public R<ProblemDetailVO> detail(@PathVariable Long id) {
        return R.ok(problemService.queryDetail(id));
    }

    @GetMapping("/{id}/versions")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "题面版本历史（归属教师/管理员）")
    public R<List<ProblemVersionVO>> versions(@PathVariable Long id) {
        return R.ok(problemService.queryVersions(id));
    }

    // ==================== 用例：题目的子资源集合 ====================

    @GetMapping("/{id}/test-cases")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "列出全部用例（含隐藏；归属教师/管理员）")
    public R<List<TestCaseVO>> listTestCases(@PathVariable Long id) {
        return R.ok(testCaseService.listAll(id));
    }

    @PostMapping("/{id}/test-cases")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "批量新增用例（含隐藏；seq 留空自动续编）")
    public R<Integer> addTestCases(@PathVariable Long id, @RequestBody List<TestCaseFormDTO> forms) {
        return R.ok(testCaseService.addCases(id, forms));
    }

    @PutMapping("/{id}/test-cases")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @Operation(summary = "全量替换用例集（破坏性：先清空再按序写入）")
    public R<Integer> replaceTestCases(@PathVariable Long id, @RequestBody List<TestCaseFormDTO> forms) {
        return R.ok(testCaseService.replaceAll(id, forms));
    }
}
