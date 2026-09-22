package com.codejudge.submission.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提交详情：提交主体 + 逐用例结果 + 编译信息。
 */
@Data
public class SubmissionDetailVO {

    private Long id;

    private Long userId;

    private Long problemId;

    private Long contestId;

    private String language;

    private String status;

    private String verdict;

    private Integer score;

    private Integer timeMs;

    private Integer memoryKb;

    private LocalDateTime submitTime;

    /** 逐用例结果（按 seq 升序） */
    private List<CaseResultVO> caseResults;

    /** 编译信息（未编译/无权限时为 null） */
    private CompileInfoVO compileInfo;

    @Data
    public static class CompileInfoVO {

        private Boolean success;

        private String stdoutLog;

        private String stderrLog;

        private Integer durationMs;
    }
}
