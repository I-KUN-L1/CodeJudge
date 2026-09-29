package com.codejudge.ai.service;

import com.codejudge.api.client.problem.ProblemClient;
import com.codejudge.api.client.submission.SubmissionClient;
import com.codejudge.api.dto.submission.SubmissionReviewContextDTO;
import com.codejudge.ai.config.ReviewProperties;
import com.codejudge.ai.domain.ReviewType;
import com.codejudge.common.constants.UserRole;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.exceptions.BizIllegalException;
import com.codejudge.common.exceptions.ForbiddenException;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewContextService 上下文装配单测（judge-ai 首个测试类）。
 *
 * <p>运行：mvn -pl judge-ai -am test
 *
 * <p>覆盖：归属鉴权三态（上游不可用 503 / 提交不存在 404 / 非本人 403）且鉴权
 * 先于向量检索执行（越权请求不消耗 Embedding 配额）、学员请求在**上游侧**遮蔽
 * 隐藏用例摘要（数据不进本服务堆内存）、隐藏用例遮蔽标记传递给模型。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewContextServiceAssembleTest {

    private static final Long OWNER_ID = 1001L;
    private static final Long OTHER_STUDENT_ID = 1002L;
    private static final Long SUBMISSION_ID = 7001L;
    private static final Long PROBLEM_ID = 4001L;

    @Mock
    private SubmissionClient submissionClient;
    @Mock
    private ProblemClient problemClient;
    @Mock
    private KnowledgeService knowledgeService;
    @Mock
    private ReviewProperties reviewProperties;

    private ReviewContextService service;

    @BeforeEach
    void setUp() {
        service = new ReviewContextService(submissionClient, problemClient,
                knowledgeService, reviewProperties);
        when(knowledgeService.search(anyString(), anyInt(), anyLong())).thenReturn(List.of());
        when(knowledgeService.searchHistory(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        // 题目摘要失败不阻断（fillProblemBrief 吞异常），这里给合法返回以覆盖正常路径
        when(problemClient.listSummaries(any())).thenReturn(List.of());
    }

    /** 归属学员 1001 的提交上下文：含一个隐藏样例（上游已按请求方视角决定是否遮蔽） */
    private SubmissionReviewContextDTO ownedDto() {
        SubmissionReviewContextDTO dto = new SubmissionReviewContextDTO();
        dto.setSubmissionId(SUBMISSION_ID);
        dto.setUserId(OWNER_ID);
        dto.setProblemId(PROBLEM_ID);
        dto.setLanguage("CPP");
        dto.setVerdict("WA");
        dto.setCode("int main() { return 0; }");

        SubmissionReviewContextDTO.CaseSample visible = new SubmissionReviewContextDTO.CaseSample();
        visible.setSeq(1);
        visible.setHidden(false);
        SubmissionReviewContextDTO.CaseSample hidden = new SubmissionReviewContextDTO.CaseSample();
        hidden.setSeq(2);
        hidden.setHidden(true);
        dto.setCaseSamples(List.of(visible, hidden));
        return dto;
    }

    @Nested
    @DisplayName("归属鉴权三态")
    class OwnershipAuth {

        @Test
        @DisplayName("学员点评他人提交 → 403，且向量检索未被触发（鉴权先于检索）")
        void otherStudentsSubmissionIsForbidden() {
            when(submissionClient.getReviewContext(eq(SUBMISSION_ID), anyBoolean()))
                    .thenReturn(ownedDto());

            assertThatThrownBy(() -> service.assemble(SUBMISSION_ID, OTHER_STUDENT_ID,
                    UserRole.STUDENT.getCode(), ReviewType.ERROR_DIAGNOSIS, null))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("只能点评自己的提交");
            verify(knowledgeService, never()).search(anyString(), anyInt(), anyLong());
        }

        @Test
        @DisplayName("上游不可用（Feign 异常）→ 业务码 503，不伪装成 404")
        void upstreamFailureMapsTo503() {
            when(submissionClient.getReviewContext(eq(SUBMISSION_ID), anyBoolean()))
                    .thenThrow(new RuntimeException("connection refused"));

            assertThatThrownBy(() -> service.assemble(SUBMISSION_ID, OWNER_ID,
                    UserRole.STUDENT.getCode(), ReviewType.ERROR_DIAGNOSIS, null))
                    .isInstanceOf(BizIllegalException.class)
                    .hasMessageContaining("无法读取该提交的判题信息")
                    .extracting(e -> ((BizIllegalException) e).getCode())
                    .isEqualTo(503);
        }

        @Test
        @DisplayName("提交不存在（上游返回 null）→ 业务码 404")
        void missingSubmissionMapsTo404() {
            when(submissionClient.getReviewContext(eq(SUBMISSION_ID), anyBoolean()))
                    .thenReturn(null);

            assertThatThrownBy(() -> service.assemble(SUBMISSION_ID, OWNER_ID,
                    UserRole.STUDENT.getCode(), ReviewType.ERROR_DIAGNOSIS, null))
                    .isInstanceOf(BizIllegalException.class)
                    .extracting(e -> ((BizIllegalException) e).getCode())
                    .isEqualTo(404);
        }

        @Test
        @DisplayName("submissionId 缺失 → 400")
        void nullSubmissionIdIsBadRequest() {
            assertThatThrownBy(() -> service.assemble(null, OWNER_ID,
                    UserRole.STUDENT.getCode(), ReviewType.ERROR_DIAGNOSIS, null))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    @Nested
    @DisplayName("隐藏用例遮蔽")
    class CaseDigestMasking {

        @Test
        @DisplayName("学员视角：请求上游时即传 mask=true（数据不进本服务堆内存），隐藏用例标记置位")
        void studentRequestMasksAtUpstream() {
            when(submissionClient.getReviewContext(eq(SUBMISSION_ID), eq(true)))
                    .thenReturn(ownedDto());

            var ctx = service.assemble(SUBMISSION_ID, OWNER_ID,
                    UserRole.STUDENT.getCode(), ReviewType.ERROR_DIAGNOSIS, null);

            verify(submissionClient).getReviewContext(SUBMISSION_ID, true);
            assertThat(ctx.isCaseDigestMasked()).isTrue();
            assertThat(ctx.getUserId()).isEqualTo(OWNER_ID);
        }

        @Test
        @DisplayName("教师视角：特权角色不遮蔽，上游收到 mask=false，隐藏用例标记不置位")
        void teacherRequestSeesFullContext() {
            when(submissionClient.getReviewContext(eq(SUBMISSION_ID), eq(false)))
                    .thenReturn(ownedDto());

            var ctx = service.assemble(SUBMISSION_ID, OWNER_ID,
                    UserRole.TEACHER.getCode(), ReviewType.CODE_REVIEW, null);

            verify(submissionClient).getReviewContext(SUBMISSION_ID, false);
            assertThat(ctx.isCaseDigestMasked()).isFalse();
        }
    }
}
