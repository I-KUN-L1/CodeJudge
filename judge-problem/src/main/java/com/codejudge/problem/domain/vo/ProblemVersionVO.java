package com.codejudge.problem.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 题面版本视图（版本历史接口使用）
 */
@Data
public class ProblemVersionVO {

    private Long id;
    private Long problemId;

    /** 版本号，从 1 递增 */
    private Integer versionNo;

    private String statement;
    private String inputSpec;
    private String outputSpec;
    private String hint;

    /** 各语言模板代码 */
    private Map<String, String> templateCode;

    /** 本版本修改人 id */
    private Long createdBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
