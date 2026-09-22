package com.codejudge.api.dto.contest;

import lombok.Data;

import java.io.Serializable;

/**
 * 竞赛内一条终态提交（judge-submission → judge-contest，供终榜重建）。
 *
 * <p>只暴露重建榜单必需的列：谁、哪题、什么结论、多少分、什么时候交的。
 * <b>不含代码与用例输出</b> —— 榜单重建没有任何理由触碰用户代码。
 */
@Data
public class ContestSubmissionDTO implements Serializable {

    private Long submissionId;

    private Long userId;

    private Long problemId;

    /** 终态结论：AC/WA/TLE/MLE/RE/CE/SE */
    private String verdict;

    /** 得分（AC 用例分值之和） */
    private Integer score;

    /** 提交时间（epoch 毫秒）—— 重建必须按此排序回放，否则「首次 AC」判定会错 */
    private Long submitTimeEpochMs;
}
