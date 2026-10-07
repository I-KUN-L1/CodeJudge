package com.codejudge.submission.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 代码草稿视图（仅本人可见）。
 *
 * <p>草稿不存在时接口返回 {@code data=null}，前端据此回退到题目模板代码。</p>
 */
@Data
public class DraftVO {

    /** 题目 id */
    private Long problemId;

    /** 语言：JAVA/PYTHON/CPP/GO */
    private String language;

    /** 代码全文 */
    private String code;

    /** 最近一次保存时间 */
    private LocalDateTime updateTime;
}
