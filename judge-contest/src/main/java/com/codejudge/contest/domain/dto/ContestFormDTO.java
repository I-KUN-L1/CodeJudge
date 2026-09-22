package com.codejudge.contest.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 竞赛创建表单。
 *
 * <p>时间语义：{@code freezeMinutes} 是**时长**而不是时刻，封榜时刻由服务端推导为
 * {@code endTime - freezeMinutes}。这样「把竞赛延长 30 分钟」不需要同时改两个字段，
 * 也就不会出现「延长了结束时间却忘了改封榜时刻」的运营事故。
 */
@Data
public class ContestFormDTO implements Serializable {

    @NotBlank(message = "竞赛标题不能为空")
    private String title;

    private String description;

    /** 赛制：ACM（罚时）/ IOI（按分）；缺省 ACM */
    private String rule;

    @NotNull(message = "开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    private LocalDateTime endTime;

    /** 封榜时长(分钟)，0=不封榜 */
    private Integer freezeMinutes;

    /** ACM 每次错误提交的罚时(分钟)，缺省 20 */
    private Integer penaltyMinutes;

    @NotEmpty(message = "竞赛至少需要一道题目")
    @Valid
    private List<ContestProblemForm> problems;

    /** 竞赛题目条目 */
    @Data
    public static class ContestProblemForm implements Serializable {

        @NotNull(message = "题目 id 不能为空")
        private Long problemId;

        /** 题号（A/B/C…）；缺省按顺序自动生成 */
        private String label;

        /** 展示顺序；缺省按列表下标 */
        private Integer displayOrder;

        /** 满分（IOI 赛制使用），缺省 100 */
        private Integer fullScore;
    }
}
