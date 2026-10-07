package com.codejudge.submission.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.utils.UserContext;
import com.codejudge.submission.domain.dto.DraftSaveDTO;
import com.codejudge.submission.domain.po.CodeDraft;
import com.codejudge.submission.domain.vo.DraftVO;
import com.codejudge.submission.mapper.CodeDraftMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 代码草稿：答案已填写完毕但尚未提交的代码，按（本人, 题目）一题一份。
 *
 * <p>定位：编辑器内容的持久化。它<b>不</b>是提交 —— 不进入判题状态机、不参与
 * 幂等去重与榜单统计。演示账户靠它实现"打开题目即见已填好的标准答案，点提交
 * 走完整真实判题链路"。</p>
 *
 * <p>越权防线：所有读写都以 {@code UserContext.getUserId()} 定位归属，
 * 问题路径上的 problemId 只是定位键，永远不信任客户端传来的 userId。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DraftService {

    /** 与 submission.code 相同的内容上限（32KB） */
    private static final int MAX_CODE_LENGTH = 32 * 1024;

    private final CodeDraftMapper draftMapper;

    /** 查询本人某题的草稿；不存在返回 null（前端据此回退题目模板） */
    public DraftVO getMyDraft(Long problemId) {
        Long userId = UserContext.getUserId();
        CodeDraft draft = draftMapper.selectOne(new LambdaQueryWrapper<CodeDraft>()
                .eq(CodeDraft::getUserId, userId)
                .eq(CodeDraft::getProblemId, problemId));
        return toVO(draft);
    }

    /** 保存（或覆盖）本人某题的草稿 */
    public void saveDraft(Long problemId, DraftSaveDTO dto) {
        Long userId = UserContext.getUserId();
        if (dto.getCode() == null || dto.getCode().trim().isEmpty()
                || dto.getCode().length() > MAX_CODE_LENGTH) {
            throw new BadRequestException("代码不能为空且不能超过 32KB");
        }
        CodeDraft draft = new CodeDraft();
        draft.setId(IdWorker.getId());
        draft.setUserId(userId);
        draft.setProblemId(problemId);
        draft.setLanguage(dto.getLanguage());
        draft.setCode(dto.getCode());
        draftMapper.upsert(draft);
    }

    /**
     * 删除本人某题的草稿（提交成功后由前端调用，草稿已转化为正式提交）。
     *
     * <p>幂等：草稿本就不存在同样按成功处理 —— 删除是"消费掉"的语义，
     * 前端重复触发、双击提交等场景不应让它变成一个刺眼的报错。</p>
     */
    public void deleteDraft(Long problemId) {
        Long userId = UserContext.getUserId();
        int removed = draftMapper.physicalDelete(userId, problemId);
        if (removed > 0) {
            log.debug("草稿已消费：userId={}, problemId={}", userId, problemId);
        }
    }

    private static DraftVO toVO(CodeDraft draft) {
        if (draft == null) {
            return null;
        }
        DraftVO vo = new DraftVO();
        vo.setProblemId(draft.getProblemId());
        vo.setLanguage(draft.getLanguage());
        vo.setCode(draft.getCode());
        vo.setUpdateTime(draft.getUpdateTime());
        return vo;
    }
}
