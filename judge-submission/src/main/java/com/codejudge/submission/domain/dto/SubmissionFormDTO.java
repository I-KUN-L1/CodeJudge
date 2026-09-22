package com.codejudge.submission.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提交表单。
 */
@Data
public class SubmissionFormDTO {

    @NotNull(message = "题目 id 不能为空")
    private Long problemId;

    /** 竞赛 id；null 视为普通提交（落库为 0） */
    private Long contestId;

    /** 语言：JAVA/PYTHON/CPP/GO（Language 枚举名） */
    @NotBlank(message = "语言不能为空")
    private String language;

    /** 代码全文（≤32KB） */
    @NotBlank(message = "代码不能为空")
    private String code;
}
