package com.codejudge.problem.controller;

import com.codejudge.api.dto.problem.JudgeCaseDTO;
import com.codejudge.api.dto.problem.JudgeInfoDTO;
import com.codejudge.api.dto.problem.ProblemSummaryDTO;
import com.codejudge.common.domain.R;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.utils.InternalOnlyGuard;
import com.codejudge.problem.domain.po.Problem;
import com.codejudge.problem.domain.po.TestCase;
import com.codejudge.problem.mapper.ProblemMapper;
import com.codejudge.problem.mapper.TestCaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * 题目内部接口（仅服务间 Feign 直连可用）。
 *
 * <p>消费方：judge-submission（提交时校验题目存在且已发布）、judge-worker（判题前拉取
 * 时间/内存限制与全部用例）。端点以 {@link InternalOnlyGuard} 保护：网关透传的任何
 * 已登录用户（含管理员）访问一律 403 —— 隐藏用例的输入输出就是题目答案，
 * 这类数据绝不允许经由任何对外接口离开 judge-problem。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/problems")
@Tag(name = "题目内部接口", description = "仅限服务间 Feign 调用（InternalOnlyGuard 保护，隐藏用例仅在此流转）")
public class InternalProblemController {

    private final ProblemMapper problemMapper;
    private final TestCaseMapper testCaseMapper;

    /**
     * 判题信息：题目限制 + 全部测试用例（按 seq 升序，含隐藏用例）。
     */
    @Operation(summary = "判题信息（内部）", description = "供 judge-submission / judge-worker 内部调用；含隐藏用例，严禁对外暴露")
    @GetMapping("/{id}/judge-info")
    public R<JudgeInfoDTO> judgeInfo(@PathVariable("id") Long id) {
        // 第一道闸：拒绝一切来自网关的外部请求（无 user-info 头才放行）
        InternalOnlyGuard.checkInternal();

        Problem problem = problemMapper.selectById(id);
        if (problem == null) {
            throw new BizIllegalException(404, "题目不存在：" + id);
        }

        List<TestCase> cases = testCaseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getProblemId, id)
                .orderByAsc(TestCase::getSeq));

        JudgeInfoDTO dto = new JudgeInfoDTO();
        dto.setProblemId(problem.getId());
        dto.setTitle(problem.getTitle());
        dto.setStatus(problem.getStatus());
        dto.setOwnerId(problem.getOwnerId());
        dto.setTimeLimitMs(problem.getTimeLimitMs());
        dto.setMemoryLimitMb(problem.getMemoryLimitMb());
        dto.setTestCases(cases.stream()
                .sorted(Comparator.comparing(TestCase::getSeq))
                .map(this::toCaseDTO)
                .toList());
        return R.ok(dto);
    }

    /**
     * 批量题目摘要（**不含用例**）：供 judge-contest 编排竞赛时校验题目存在且已发布。
     *
     * <p>单独开一个端点而不复用 {@code /judge-info}：后者会返回全部隐藏用例
     * （即题目答案），而编排只需要「存在性 + 标题 + 状态」。数据最小化在这里是硬约束
     * —— 让答案不必要地流过一个与判题无关的链路，等于人为扩大泄漏面。
     */
    @Operation(summary = "题目摘要（内部）", description = "供 judge-contest 校验题目存在性与展示标题；不含任何用例")
    @GetMapping("/summaries")
    public R<List<ProblemSummaryDTO>> summaries(@RequestParam("ids") List<Long> ids) {
        InternalOnlyGuard.checkInternal();
        if (ids == null || ids.isEmpty()) {
            return R.ok(List.of());
        }
        List<Problem> problems = problemMapper.selectBatchIds(ids);
        return R.ok(problems.stream().map(p -> {
            ProblemSummaryDTO dto = new ProblemSummaryDTO();
            dto.setProblemId(p.getId());
            dto.setTitle(p.getTitle());
            dto.setStatus(p.getStatus());
            dto.setDifficulty(p.getDifficulty());
            dto.setOwnerId(p.getOwnerId());
            return dto;
        }).toList());
    }

    private JudgeCaseDTO toCaseDTO(TestCase c) {
        JudgeCaseDTO dto = new JudgeCaseDTO();
        dto.setCaseId(c.getId());
        dto.setSeq(c.getSeq());
        dto.setStdin(c.getStdin());
        dto.setExpectedStdout(c.getExpectedStdout());
        dto.setIsHidden(c.getIsHidden());
        dto.setScore(c.getScore());
        dto.setTimeLimitMs(c.getTimeLimitMs());
        dto.setJudgeMode(c.getJudgeMode());
        return dto;
    }
}
