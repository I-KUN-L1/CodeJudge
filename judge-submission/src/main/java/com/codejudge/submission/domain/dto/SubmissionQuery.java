package com.codejudge.submission.domain.dto;

import com.codejudge.common.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 提交分页查询。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SubmissionQuery extends PageQuery {

    /** 按题目过滤 */
    private Long problemId;

    /** 按提交人过滤（学员视角强制为自己，传了也忽略） */
    private Long userId;

    /** 按状态过滤：PENDING/JUDGING/SUCCESS/FAILED */
    private String status;

    /** 按结论过滤：AC/WA/TLE/MLE/RE/CE/SE */
    private String verdict;
}
