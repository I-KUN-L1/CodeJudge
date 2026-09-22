package com.codejudge.problem.controller;

import com.codejudge.common.annotation.RequireRole;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.domain.R;
import com.codejudge.problem.domain.dto.TagFormDTO;
import com.codejudge.problem.domain.vo.TagVO;
import com.codejudge.problem.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 题目标签
 *
 * <p>标签是全局共享字典，因此增删收敛到管理员（STAFF）；
 * 教师建题时只需从已有标签里挑，不需要自己造标签。
 */
@RestController
@RequestMapping("/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @GetMapping
    @Operation(summary = "标签列表（登录可见，可按类型过滤）")
    public R<List<TagVO>> list(@RequestParam(required = false) String type) {
        return R.ok(tagService.listAll(type));
    }

    @PostMapping
    @RequireRole(UserRole.STAFF)
    @Operation(summary = "新建标签（管理员）")
    public R<Long> create(@RequestBody TagFormDTO form) {
        return R.ok(tagService.create(form));
    }

    @DeleteMapping("/{id}")
    @RequireRole(UserRole.STAFF)
    @Operation(summary = "删除标签（管理员；被题目引用时拒绝）")
    public R<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return R.ok();
    }
}
