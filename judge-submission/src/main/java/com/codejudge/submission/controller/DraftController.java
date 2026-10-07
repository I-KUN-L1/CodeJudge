package com.codejudge.submission.controller;

import com.codejudge.common.domain.R;
import com.codejudge.submission.domain.dto.DraftSaveDTO;
import com.codejudge.submission.domain.vo.DraftVO;
import com.codejudge.submission.service.DraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 代码草稿接口（网关前缀 /submissions/drafts/**，随 /submissions/** 路由到本服务）。
 *
 * <p>仅操作<b>本人</b>草稿：归属由服务层按登录上下文强制，路径只带 problemId。</p>
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/submissions/drafts")
@Tag(name = "代码草稿", description = "答案已填写但尚未提交的代码，按（本人, 题目）一题一份")
public class DraftController {

    private final DraftService draftService;

    @Operation(summary = "查询本人某题草稿", description = "不存在时 data=null，前端回退到题目模板代码")
    @GetMapping("/{problemId}")
    public R<DraftVO> get(@PathVariable("problemId") Long problemId) {
        return R.ok(draftService.getMyDraft(problemId));
    }

    @Operation(summary = "保存本人某题草稿", description = "按（本人, 题目）幂等覆盖；代码上限 32KB")
    @PutMapping("/{problemId}")
    public R<Void> save(@PathVariable("problemId") Long problemId,
                        @Valid @RequestBody DraftSaveDTO dto) {
        draftService.saveDraft(problemId, dto);
        return R.ok();
    }

    @Operation(summary = "删除本人某题草稿", description = "提交成功后调用；物理删除，幂等（不存在也成功）")
    @DeleteMapping("/{problemId}")
    public R<Void> delete(@PathVariable("problemId") Long problemId) {
        draftService.deleteDraft(problemId);
        return R.ok();
    }
}
