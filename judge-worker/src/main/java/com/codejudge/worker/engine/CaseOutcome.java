package com.codejudge.worker.engine;

import lombok.Data;

/**
 * 单用例执行判定结果。
 */
@Data
public class CaseOutcome {

    private Long caseId;

    private Integer seq;

    /** AC/WA/TLE/MLE/RE */
    private Verdict verdict;

    private int timeMs;

    private long memKb;

    /** 实际输出摘要（截断；隐藏用例回传摘要供审计，下发遮蔽由 submission 服务控制） */
    private String outputDigest;

    private String stderrDigest;
}
