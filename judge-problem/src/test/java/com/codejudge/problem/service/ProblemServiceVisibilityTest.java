package com.codejudge.problem.service;

import com.codejudge.common.constants.UserRole;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.ForbiddenException;
import com.codejudge.common.utils.UserContext;
import com.codejudge.problem.domain.po.Problem;
import com.codejudge.problem.domain.po.ProblemVersion;
import com.codejudge.problem.domain.po.TestCase;
import com.codejudge.problem.domain.vo.ProblemDetailVO;
import com.codejudge.problem.mapper.ProblemMapper;
import com.codejudge.problem.mapper.ProblemTagMapper;
import com.codejudge.problem.mapper.ProblemVersionMapper;
import com.codejudge.problem.mapper.TagMapper;
import com.codejudge.problem.mapper.TestCaseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ProblemService 可见性隔离单元测试（judge-problem 首个测试类）。
 *
 * <p>运行：mvn -pl judge-problem -am test
 *
 * <p>覆盖类注释的三条安全边界中最核心的两条：
 * 隐藏用例按视角分级下发（非归属视角 testCases/hiddenCaseCount 为 null 而非空数组）、
 * 未发布题目对非归属者等同不存在（不泄露"题目存在"这一信息本身）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProblemServiceVisibilityTest {

    private static final Long TEACHER_ID = 3001L;
    private static final Long STUDENT_ID = 1001L;
    private static final Long OTHER_STUDENT_ID = 1002L;
    private static final Long PROBLEM_ID = 4001L;

    @Mock
    private ProblemMapper problemMapper;
    @Mock
    private ProblemVersionMapper problemVersionMapper;
    @Mock
    private TestCaseMapper testCaseMapper;
    @Mock
    private TagMapper tagMapper;
    @Mock
    private ProblemTagMapper problemTagMapper;

    private ProblemService service;

    @BeforeEach
    void setUp() {
        service = new ProblemService(problemMapper, problemVersionMapper,
                testCaseMapper, tagMapper, problemTagMapper);
        // loadTags 走关联表查询：默认 mock 返回 null 会在 isEmpty() 处 NPE，显式给空集
        when(problemTagMapper.selectList(any())).thenReturn(List.of());
        when(problemVersionMapper.selectOne(any())).thenReturn(null);
    }

    @AfterEach
    void clearContext() {
        UserContext.remove();
    }

    private Problem problem(int status) {
        Problem p = new Problem();
        p.setId(PROBLEM_ID);
        p.setOwnerId(TEACHER_ID);
        p.setStatus(status);
        return p;
    }

    private TestCase testCase(int seq, Integer hidden) {
        TestCase c = new TestCase();
        c.setProblemId(PROBLEM_ID);
        c.setSeq(seq);
        c.setIsHidden(hidden);
        return c;
    }

    private void loginAs(Long userId, UserRole role) {
        UserContext.setUser(userId);
        UserContext.setRole(role.getCode());
    }

    @Nested
    @DisplayName("隐藏用例分级下发（安全边界 1）")
    class HiddenCaseMasking {

        @BeforeEach
        void stubPublishedProblemWithCases() {
            when(problemMapper.selectById(PROBLEM_ID)).thenReturn(problem(ProblemService.STATUS_PUBLISHED));
            when(testCaseMapper.selectList(any())).thenReturn(List.of(
                    testCase(1, 0), testCase(2, 1), testCase(3, null)));
        }

        @Test
        @DisplayName("学员视角：samples 只含可见用例，全量用例字段为 null 而非空数组")
        void studentViewMasksHiddenCases() {
            loginAs(STUDENT_ID, UserRole.STUDENT);

            ProblemDetailVO vo = service.queryDetail(PROBLEM_ID);

            assertThat(vo.getSamples()).extracting("seq").containsExactly(1, 3);
            assertThat(vo.getTestCases()).isNull();
            assertThat(vo.getHiddenCaseCount()).isNull();
        }

        @Test
        @DisplayName("归属教师视角：全量用例下发，隐藏用例数 = 1")
        void ownerViewSeesAllCases() {
            loginAs(TEACHER_ID, UserRole.TEACHER);

            ProblemDetailVO vo = service.queryDetail(PROBLEM_ID);

            assertThat(vo.getTestCases()).hasSize(3);
            assertThat(vo.getHiddenCaseCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("管理员视角：等同归属方，全量用例下发")
        void staffViewSeesAllCases() {
            loginAs(OTHER_STUDENT_ID, UserRole.STAFF);

            ProblemDetailVO vo = service.queryDetail(PROBLEM_ID);

            assertThat(vo.getTestCases()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("未发布题目不外泄（安全边界 2）")
    class DraftVisibility {

        @Test
        @DisplayName("草稿/下线题对非归属学员抛 403，等同不存在")
        void draftHiddenFromOtherStudents() {
            when(problemMapper.selectById(PROBLEM_ID)).thenReturn(problem(ProblemService.STATUS_DRAFT));
            loginAs(OTHER_STUDENT_ID, UserRole.STUDENT);

            assertThatThrownBy(() -> service.queryDetail(PROBLEM_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("题目不存在或未发布");
        }

        @Test
        @DisplayName("未发布题对归属教师可见（写题人需要回看草稿）")
        void draftVisibleToOwner() {
            when(problemMapper.selectById(PROBLEM_ID)).thenReturn(problem(ProblemService.STATUS_DRAFT));
            when(testCaseMapper.selectList(any())).thenReturn(List.of());
            loginAs(TEACHER_ID, UserRole.TEACHER);

            assertThat(service.queryDetail(PROBLEM_ID).getId()).isEqualTo(PROBLEM_ID);
        }
    }

    @Test
    @DisplayName("题目不存在 → 400（而非 403，避免向登录用户泄露 id 探测结果）")
    void missingProblemIsBadRequest() {
        when(problemMapper.selectById(PROBLEM_ID)).thenReturn(null);
        loginAs(STUDENT_ID, UserRole.STUDENT);

        assertThatThrownBy(() -> service.queryDetail(PROBLEM_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("题目不存在");
    }

    @Test
    @DisplayName("isOwnerOrStaff：无 user-info 头的服务间 Feign 调用视为可（判题侧依赖此语义）")
    void internalCallIsTreatedAsOwner() {
        UserContext.remove();

        assertThat(service.isOwnerOrStaff(problem(ProblemService.STATUS_PUBLISHED))).isTrue();
    }
}
