package com.codejudge.api.dto.contest;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 竞赛上下文（内部 Feign 契约，judge-contest → judge-submission）。
 *
 * <p>解决的问题：P3 的提交接口对 {@code contestId} 只做透传，任何登录用户都能带着
 * 任意竞赛 id 提交（哪怕是未开始/已结束的竞赛，或不存在的竞赛）。竞赛榜一旦上线，
 * 这种提交会直接污染排行榜。因此提交前必须向竞赛服务确认：
 * <pre>
 *   竞赛存在 且 处于进行中 且 题目在该竞赛内 且 （需要报名时）用户已报名
 * </pre>
 *
 * <p>字段刻意保持精简：只给提交校验与判题进度展示所需的最小集合，
 * 竞赛的完整信息（描述、榜单、快照）由 judge-contest 自己的对外接口提供。
 */
@Data
public class ContestContextDTO implements Serializable {

    /** 竞赛 id */
    private Long contestId;

    /** 竞赛标题（进度推送里可直接展示） */
    private String title;

    /** 赛制：ACM（罚时）/ IOI（按分） */
    private String rule;

    /**
     * 竞赛状态：0=未开始 1=进行中 2=已结束。
     *
     * <p>由竞赛服务**按当前时间实时推导**（而非直接读库里的 status 列），
     * 避免扫描任务尚未落库时把刚开始的竞赛判成未开始。
     */
    private Integer status;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 封榜时刻（= end_time - freeze_minutes）；无封榜时为 null */
    private LocalDateTime freezeAt;

    /** 封榜时长(分钟)，0=不封榜 */
    private Integer freezeMinutes;

    /** ACM 每次错误提交的罚时(分钟) */
    private Integer penaltyMinutes;

    /** 请求携带 userId 时：该用户是否已报名（status=1 的报名记录） */
    private Boolean registered;

    /**
     * 本竞赛是否要求报名后才能提交。
     *
     * <p>由 judge-contest 的 {@code cj.contest.require-registration} 决定并下发 ——
     * 是否要求报名的开关属于竞赛域配置，judge-submission 不重复定义一份，
     * 否则两处配置漂移会出现「后端拒绝提交但前端说无需报名」。
     */
    private Boolean needRegister;

    /** 竞赛题目 id 列表（提交校验用：题目必须属于本竞赛） */
    private List<Long> problemIds;

    /** 题目满分表：key=problemId(字符串形式，JSON 对象键只能是字符串) → value=满分 */
    private Map<String, Integer> fullScores;
}
