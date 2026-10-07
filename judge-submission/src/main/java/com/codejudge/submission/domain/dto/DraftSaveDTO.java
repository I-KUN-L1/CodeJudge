package com.codejudge.submission.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存代码草稿的请求体。
 *
 * <p>长度上限与 {@code submission.code} 一致（32KB）：草稿转正式提交时内容原样进入
 * 提交流程，这里放宽只会把报错推迟到提交时。
 */
@Data
public class DraftSaveDTO {

    /** 语言：JAVA/PYTHON/CPP/GO */
    @NotBlank
    private String language;

    /** 代码全文（≤32KB） */
    @NotNull
    @Size(max = 32768, message = "代码不能超过 32KB")
    private String code;
}
