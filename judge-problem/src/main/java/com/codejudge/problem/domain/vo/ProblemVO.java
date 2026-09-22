package com.codejudge.problem.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.codejudge.problem.domain.po.Problem;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 题目列表视图（不含题面与用例）
 *
 * <p>列表接口**一律不下发测试用例**（无论隐藏与否）—— 列表页没有展示用例的需求，
 * 少查一张表也就少一个泄露面。
 */
@Data
public class ProblemVO {

    private Long id;
    private String title;
    private Integer difficulty;
    private Integer timeLimitMs;
    private Integer memoryLimitMb;
    private Integer status;
    private Long ownerId;
    private Long currentVersionId;
    private Integer submitCount;
    private Integer acceptedCount;

    /**
     * 通过率（百分比，保留 1 位小数）。
     * 提交数为 0 时为 null —— 展示 "0%" 会让未有人做过的题看起来像"没人能过"。
     */
    private Double acceptedRate;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /** 标签列表（由 problem_tag 关联表批量装配，避免列表页 N+1 查询） */
    private List<TagVO> tags;

    public static ProblemVO of(Problem p) {
        return fill(new ProblemVO(), p);
    }

    /**
     * 将题目主体的公共字段填充到目标视图实例。
     * <p>抽出来是为了让子类 {@link ProblemDetailVO} 复用同一份映射逻辑 ——
     * 否则详情视图要重复 12 行 setter，字段增减时两处必然漂移。
     *
     * @param vo 目标视图（可以是 ProblemVO 或其子类实例）
     * @param p  题目实体
     * @return 传入的 vo，便于链式使用
     */
    public static <T extends ProblemVO> T fill(T vo, Problem p) {
        vo.setId(p.getId());
        vo.setTitle(p.getTitle());
        vo.setDifficulty(p.getDifficulty());
        vo.setTimeLimitMs(p.getTimeLimitMs());
        vo.setMemoryLimitMb(p.getMemoryLimitMb());
        vo.setStatus(p.getStatus());
        vo.setOwnerId(p.getOwnerId());
        vo.setCurrentVersionId(p.getCurrentVersionId());
        vo.setSubmitCount(p.getSubmitCount());
        vo.setAcceptedCount(p.getAcceptedCount());
        vo.setAcceptedRate(rate(p.getAcceptedCount(), p.getSubmitCount()));
        vo.setCreateTime(p.getCreateTime());
        vo.setUpdateTime(p.getUpdateTime());
        return vo;
    }

    /**
     * 通过率计算：分母为 0 返回 null。
     * 用整数运算四舍五入到 1 位小数，避免 double 直接相除产生的 33.33333333333333 之类噪声。
     */
    static Double rate(Integer accepted, Integer submit) {
        if (submit == null || submit <= 0) {
            return null;
        }
        int acc = accepted == null ? 0 : accepted;
        return Math.round(acc * 1000.0 / submit) / 10.0;
    }
}
