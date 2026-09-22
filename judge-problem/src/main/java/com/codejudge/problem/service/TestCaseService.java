package com.codejudge.problem.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.utils.UserContext;
import com.codejudge.problem.domain.dto.TestCaseFormDTO;
import com.codejudge.problem.domain.po.TestCase;
import com.codejudge.problem.domain.vo.TestCaseVO;
import com.codejudge.problem.mapper.TestCaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 测试用例服务（含隐藏用例）
 *
 * <p>本服务的**每一个公开方法**都以 {@link ProblemService#getOwnedProblem(Long)} 开头 ——
 * 用例集合是题目的答案，任何用例读写都必须先证明"这是我（或管理员）的题"。
 * 唯一的例外是 {@link ProblemService#queryDetail} 内部对可见样例的读取，
 * 它走 Mapper 直连并已按 {@code is_hidden = 0} 过滤。
 *
 * <p>删除一律走物理删除，理由见 {@link TestCaseMapper#deletePhysicallyByProblemId}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TestCaseService {

    /** 比对模式：精确 */
    private static final int JUDGE_MODE_EXACT = 0;

    /** 分数 / 时间限制的合理上界，防止误传导致判题机资源被拉爆 */
    private static final int MAX_SCORE = 10000;
    private static final int MAX_CASE_TIME_LIMIT_MS = 60000;

    private final TestCaseMapper testCaseMapper;
    private final ProblemService problemService;

    /**
     * 批量新增用例（归属教师 / 管理员）。
     *
     * <p>不传 {@code seq} 时按"当前最大序号 + 1"自动续编，避免前端手工维护序号。
     *
     * @return 实际新增条数
     */
    @Transactional(rollbackFor = Exception.class)
    public int addCases(Long problemId, List<TestCaseFormDTO> forms) {
        problemService.getOwnedProblem(problemId);
        if (forms == null || forms.isEmpty()) {
            throw new BadRequestException("用例列表不能为空");
        }
        int nextSeq = nextSeq(problemId);
        for (TestCaseFormDTO form : forms) {
            TestCase entity = new TestCase();
            entity.setProblemId(problemId);
            entity.setSeq(form.getSeq() != null ? form.getSeq() : nextSeq++);
            applyForm(entity, form);
            entity.setCreater(UserContext.getUser());
            insertIgnoringDuplicateSeq(entity);
        }
        log.info("新增用例：problemId={}, count={}, operatorId={}",
                problemId, forms.size(), UserContext.getUserId());
        return forms.size();
    }

    /**
     * 全量替换用例集（归属教师 / 管理员）。
     *
     * <p>用于"把这道题的用例一次性换成另一套"的场景：先物理清空再按顺序重排 seq。
     * 注意这是**破坏性**操作，已产生的判题结果不受影响（结果存的是快照），
     * 但重判历史提交会按新用例集判定。
     */
    @Transactional(rollbackFor = Exception.class)
    public int replaceAll(Long problemId, List<TestCaseFormDTO> forms) {
        problemService.getOwnedProblem(problemId);
        if (forms == null || forms.isEmpty()) {
            throw new BadRequestException("用例列表不能为空");
        }
        testCaseMapper.deletePhysicallyByProblemId(problemId);
        int seq = 1;
        for (TestCaseFormDTO form : forms) {
            TestCase entity = new TestCase();
            entity.setProblemId(problemId);
            entity.setSeq(form.getSeq() != null ? form.getSeq() : seq);
            seq = Math.max(seq, entity.getSeq()) + 1;
            applyForm(entity, form);
            entity.setCreater(UserContext.getUser());
            insertIgnoringDuplicateSeq(entity);
        }
        log.warn("全量替换用例集：problemId={}, count={}, operatorId={}",
                problemId, forms.size(), UserContext.getUserId());
        return forms.size();
    }

    /**
     * 修改单个用例（归属教师 / 管理员）。仅覆盖非 null 字段。
     */
    public void updateCase(Long caseId, TestCaseFormDTO form) {
        if (form == null) {
            throw new BadRequestException("用例信息不能为空");
        }
        TestCase entity = requireCase(caseId);
        // 归属校验：用例所属题目的归属方才能改
        problemService.getOwnedProblem(entity.getProblemId());

        if (form.getSeq() != null) {
            if (form.getSeq() < 1) {
                throw new BadRequestException("用例序号必须从 1 开始");
            }
            entity.setSeq(form.getSeq());
        }
        if (form.getStdin() != null) {
            entity.setStdin(form.getStdin());
        }
        if (form.getExpectedStdout() != null) {
            entity.setExpectedStdout(form.getExpectedStdout());
        }
        if (form.getIsHidden() != null) {
            checkHidden(form.getIsHidden());
            entity.setIsHidden(form.getIsHidden());
        }
        if (form.getScore() != null) {
            checkScore(form.getScore());
            entity.setScore(form.getScore());
        }
        if (form.getTimeLimitMs() != null) {
            checkCaseTimeLimit(form.getTimeLimitMs());
            entity.setTimeLimitMs(form.getTimeLimitMs());
        }
        if (form.getJudgeMode() != null) {
            checkJudgeMode(form.getJudgeMode());
            entity.setJudgeMode(form.getJudgeMode());
        }
        entity.setUpdater(UserContext.getUser());
        try {
            testCaseMapper.updateById(entity);
        } catch (DuplicateKeyException e) {
            throw new BizIllegalException("用例序号已被占用：seq=" + entity.getSeq());
        }
    }

    /**
     * 删除单个用例（归属教师 / 管理员）。物理删除，序号可被后续新增复用。
     */
    public void deleteCase(Long caseId) {
        TestCase entity = requireCase(caseId);
        problemService.getOwnedProblem(entity.getProblemId());
        testCaseMapper.deletePhysicallyById(caseId);
        log.info("删除用例：caseId={}, problemId={}, seq={}, operatorId={}",
                caseId, entity.getProblemId(), entity.getSeq(), UserContext.getUserId());
    }

    /**
     * 列出某题目的**全部**用例（含隐藏用例）。
     * <p>仅归属教师 / 管理员可调用 —— 学员视角请走 {@link ProblemService#queryDetail} 的 samples。
     */
    public List<TestCaseVO> listAll(Long problemId) {
        problemService.getOwnedProblem(problemId);
        return testCaseMapper.selectList(new LambdaQueryWrapper<TestCase>()
                        .eq(TestCase::getProblemId, problemId)
                        .orderByAsc(TestCase::getSeq))
                .stream().map(TestCaseVO::of).toList();
    }

    // ==================== 内部工具 ====================

    private TestCase requireCase(Long caseId) {
        TestCase entity = testCaseMapper.selectById(caseId);
        if (entity == null) {
            throw new BadRequestException("用例不存在");
        }
        return entity;
    }

    /**
     * 插入并兜底唯一键冲突。
     * <p>并发调用下"取最大 seq + 1"会撞 {@code uk_test_case_seq}，
     * 把数据库异常翻译成可读提示而不是暴露 500。
     */
    private void insertIgnoringDuplicateSeq(TestCase entity) {
        checkHidden(entity.getIsHidden());
        checkScore(entity.getScore());
        checkCaseTimeLimit(entity.getTimeLimitMs());
        checkJudgeMode(entity.getJudgeMode());
        try {
            testCaseMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new BizIllegalException("用例序号重复：seq=" + entity.getSeq()
                    + "（同一题目内序号必须唯一，可留空由服务端自动续编）");
        }
    }

    private void applyForm(TestCase entity, TestCaseFormDTO form) {
        entity.setStdin(form.getStdin());
        entity.setExpectedStdout(form.getExpectedStdout());
        entity.setIsHidden(form.getIsHidden() == null ? 0 : form.getIsHidden());
        entity.setScore(form.getScore() == null ? 0 : form.getScore());
        entity.setTimeLimitMs(form.getTimeLimitMs());
        entity.setJudgeMode(form.getJudgeMode() == null ? JUDGE_MODE_EXACT : form.getJudgeMode());
    }

    private int nextSeq(Long problemId) {
        TestCase latest = testCaseMapper.selectOne(new LambdaQueryWrapper<TestCase>()
                .eq(TestCase::getProblemId, problemId)
                .orderByDesc(TestCase::getSeq)
                .last("limit 1"));
        return (latest == null || latest.getSeq() == null) ? 1 : latest.getSeq() + 1;
    }

    private void checkHidden(Integer isHidden) {
        if (isHidden != null && isHidden != 0 && isHidden != 1) {
            throw new BadRequestException("isHidden 取值不合法：0-可见 1-隐藏");
        }
    }

    private void checkScore(Integer score) {
        if (score != null && (score < 0 || score > MAX_SCORE)) {
            throw new BadRequestException("用例分值必须在 0~" + MAX_SCORE + " 之间");
        }
    }

    private void checkCaseTimeLimit(Integer ms) {
        if (ms != null && (ms < 1 || ms > MAX_CASE_TIME_LIMIT_MS)) {
            throw new BadRequestException("用例时间限制必须在 1~" + MAX_CASE_TIME_LIMIT_MS + " 毫秒之间");
        }
    }

    private void checkJudgeMode(Integer mode) {
        if (mode != null && (mode < 0 || mode > 2)) {
            throw new BadRequestException("比对模式取值不合法：0-精确 1-浮点容差 2-特判");
        }
    }
}
