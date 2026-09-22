package com.codejudge.contest.domain.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 榜单视图（REST 与 WebSocket 共用同一份结构）。
 *
 * <p><b>封榜语义落在两个字段上</b>：
 * <ul>
 *   <li>{@code frozen}：**当前视图是否为冻结榜** —— 封榜期间公众看到的值恒为 true，
 *       名单停在封榜时刻；教师/管理员的 {@code full} 视图为 false（看实时）；</li>
 *   <li>{@code fullView}：本响应是否绕过了封榜限制（仅内部角色可得）。
 *       前端据此决定是否显示「封榜中」水印。</li>
 * </ul>
 * 把两个语义分开表达，是为了避免出现「管理员看到 frozen=true 于是也以为自己在看冻结榜」
 * 这类因字段复用导致的误判。
 */
@Data
public class ContestRankVO implements Serializable {

    private Long contestId;
    private String title;

    /** 赛制：ACM / IOI */
    private String rule;

    /** 竞赛实时状态：0未开始 1进行中 2已结束 */
    private Integer status;

    /** 封榜时刻 */
    private LocalDateTime freezeAt;

    /** 当前是否处于封榜时段（与视图无关的客观事实） */
    private Boolean inFreezeWindow;

    /** 本视图是否为冻结榜（公众视图在封榜期间恒为 true） */
    private Boolean frozen;

    /** 本响应是否绕过了封榜（内部全量视图） */
    private Boolean fullView;

    /** 榜单版本号（同主题单调递增）：客户端据此判断刷新与丢包 */
    private Long version;

    /** 榜单生成时刻 */
    private LocalDateTime updatedAt;

    /** 参赛人数（有成绩记录的人数） */
    private Long totalParticipants;

    /** 榜单条目（默认 top-N，封榜冻结榜同样只含封榜时刻的 top-N） */
    private List<RankEntryVO> entries;

    /** 请求者本人的名次（未参赛为 null） */
    private Integer myRank;

    /** 请求者本人的榜行（未参赛为 null） */
    private RankEntryVO myEntry;

    /** 题号顺序（A/B/C…），供前端表头渲染 */
    private List<String> labels;
}
