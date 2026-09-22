package com.codejudge.contest.controller;

import com.codejudge.common.annotation.RequireRole;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.PageDTO;
import com.codejudge.common.domain.R;
import com.codejudge.contest.domain.dto.ContestFormDTO;
import com.codejudge.contest.domain.dto.ContestQuery;
import com.codejudge.contest.domain.vo.ContestDetailVO;
import com.codejudge.contest.domain.vo.ContestVO;
import com.codejudge.contest.service.ContestService;
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
 * 竞赛管理接口（网关前缀 /contests/**）。
 */
@Slf4j
@RestController
@RequestMapping("/contests")
@RequiredArgsConstructor
@Tag(name = "竞赛管理", description = "竞赛创建、列表、详情、报名")
public class ContestController {

    private final ContestService contestService;

    @Operation(summary = "创建竞赛", description = "教师/管理员；题目需存在且已发布，封榜时刻由结束时间与封榜时长推导")
    @RequireRole({UserRole.STAFF, UserRole.TEACHER})
    @PostMapping
    public R<ContestDetailVO> create(@Valid @RequestBody ContestFormDTO form) {
        return R.ok(contestService.create(form));
    }

    @Operation(summary = "竞赛分页", description = "按关键词与状态（时间区间翻译，非直接过滤状态列）分页")
    @GetMapping("/page")
    public R<PageDTO<ContestVO>> page(ContestQuery query) {
        return R.ok(contestService.page(query));
    }

    @Operation(summary = "竞赛详情", description = "含题目编排（题号/满分/标题）与报名信息")
    @GetMapping("/{id}")
    public R<ContestDetailVO> detail(@PathVariable("id") Long id) {
        return R.ok(contestService.detail(id));
    }

    @Operation(summary = "竞赛报名", description = "已结束的竞赛不可报名；重复报名幂等")
    @PostMapping("/{id}/register")
    public R<Void> register(@PathVariable("id") Long id) {
        contestService.register(id);
        return R.ok();
    }
}
