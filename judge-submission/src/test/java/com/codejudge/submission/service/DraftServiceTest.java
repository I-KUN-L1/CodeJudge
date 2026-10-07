package com.codejudge.submission.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.common.utils.UserContext;
import com.codejudge.submission.domain.dto.DraftSaveDTO;
import com.codejudge.submission.domain.po.CodeDraft;
import com.codejudge.submission.domain.vo.DraftVO;
import com.codejudge.submission.mapper.CodeDraftMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 代码草稿服务测试：答案已填写但尚未提交的演示数据链路。
 *
 * <p>重点钉住两件事：
 * <ul>
 *   <li><b>归属防线</b> —— 草稿的 user 永远取登录上下文，绝不信任客户端；</li>
 *   <li><b>存删语义</b> —— upsert 幂等覆盖、删除物理删（保证种子/恢复脚本可重灌唯一键位）且幂等。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class DraftServiceTest {

    private static final Long USER_ID = 2999L;
    private static final Long PROBLEM_ID = 4001L;

    @Mock
    private CodeDraftMapper draftMapper;

    private DraftService service;

    @BeforeAll
    static void initMpTableInfo() {
        // LambdaQueryWrapper 需要 MP 的实体 lambda 缓存，纯单测环境手工初始化
        MybatisConfiguration cfg = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, CodeDraft.class);
    }

    @BeforeEach
    void setUp() {
        UserContext.setUser(USER_ID);
        UserContext.setRole(2); // 学员
        service = new DraftService(draftMapper);
    }

    @AfterEach
    void tearDown() {
        UserContext.remove();
    }

    private static DraftSaveDTO form(String language, String code) {
        DraftSaveDTO dto = new DraftSaveDTO();
        dto.setLanguage(language);
        dto.setCode(code);
        return dto;
    }

    @Nested
    @DisplayName("查询草稿")
    class Get {

        @Test
        void returnsNullWhenNoDraft() {
            when(draftMapper.selectOne(any())).thenReturn(null);
            assertThat(service.getMyDraft(PROBLEM_ID)).isNull();
        }

        @Test
        void mapsPoToVo() {
            CodeDraft po = new CodeDraft();
            po.setUserId(USER_ID);
            po.setProblemId(PROBLEM_ID);
            po.setLanguage("JAVA");
            po.setCode("public class Main{}");
            po.setUpdateTime(LocalDateTime.of(2026, 10, 7, 12, 0));
            when(draftMapper.selectOne(any())).thenReturn(po);

            DraftVO vo = service.getMyDraft(PROBLEM_ID);

            assertThat(vo.getProblemId()).isEqualTo(PROBLEM_ID);
            assertThat(vo.getLanguage()).isEqualTo("JAVA");
            assertThat(vo.getCode()).isEqualTo("public class Main{}");
        }
    }

    @Nested
    @DisplayName("保存草稿")
    class Save {

        @Test
        void upsertAnchorsUserIdFromContext() {
            // 归属防线：service 必须用登录上下文的 userId，DTO/路径上都传不进别人的 id
            service.saveDraft(PROBLEM_ID, form("JAVA", "long a, b;"));

            ArgumentCaptor<CodeDraft> captor = ArgumentCaptor.forClass(CodeDraft.class);
            verify(draftMapper).upsert(captor.capture());
            CodeDraft saved = captor.getValue();
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getProblemId()).isEqualTo(PROBLEM_ID);
            assertThat(saved.getLanguage()).isEqualTo("JAVA");
            assertThat(saved.getCode()).isEqualTo("long a, b;");
            assertThat(saved.getId()).as("upsert 走显式 SQL，id 需由服务侧生成").isNotNull();
        }

        @Test
        void rejectsBlankCode() {
            assertThatThrownBy(() -> service.saveDraft(PROBLEM_ID, form("JAVA", "  ")))
                    .isInstanceOf(BadRequestException.class);
            verifyNoInteractions(draftMapper);
        }

        @Test
        void rejectsOversizeCode() {
            assertThatThrownBy(() -> service.saveDraft(PROBLEM_ID, form("JAVA", "a".repeat(32 * 1024 + 1))))
                    .isInstanceOf(BadRequestException.class);
            verifyNoInteractions(draftMapper);
        }
    }

    @Nested
    @DisplayName("删除草稿")
    class Delete {

        @Test
        void deletesPhysicallyForCurrentUserAndProblem() {
            when(draftMapper.physicalDelete(USER_ID, PROBLEM_ID)).thenReturn(1);
            service.deleteDraft(PROBLEM_ID);
            verify(draftMapper).physicalDelete(USER_ID, PROBLEM_ID);
        }

        @Test
        void missingDraftIsIdempotentSuccess() {
            // 删除 = "消费掉"的语义：不存在（重复触发/双击提交）也按成功处理
            when(draftMapper.physicalDelete(USER_ID, PROBLEM_ID)).thenReturn(0);
            service.deleteDraft(PROBLEM_ID);
            verify(draftMapper).physicalDelete(USER_ID, PROBLEM_ID);
        }
    }

    @Test
    void saveWithNullCodeIsRejected() {
        DraftSaveDTO dto = new DraftSaveDTO();
        dto.setLanguage("JAVA");
        assertThatThrownBy(() -> service.saveDraft(PROBLEM_ID, dto))
                .isInstanceOf(BadRequestException.class);
        verify(draftMapper, never()).upsert(any());
    }
}
