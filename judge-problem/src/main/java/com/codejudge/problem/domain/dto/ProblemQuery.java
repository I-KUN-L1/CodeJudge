package com.codejudge.problem.domain.dto;

import com.codejudge.common.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 题目列表查询条件
 *
 * <p>继承 {@link PageQuery} 以获得 pageNo / pageSize / sortBy / isAsc，
 * 由 Spring 直接从 query string 绑定，避免 Controller 上堆一长串 {@code @RequestParam}。
 *
 * <p>可见性提示：{@link #status} 与 {@link #onlyMine} 由服务端按角色二次收敛 ——
 * 学员传了 {@code status=0} 也只会看到已发布题目（见 ProblemService#pageQuery）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProblemQuery extends PageQuery {

    /** 标题关键字（模糊匹配） */
    private String keyword;

    /** 难度精确过滤：1~5 */
    private Integer difficulty;

    /** 标签 id 过滤 */
    private Long tagId;

    /** 状态过滤：0-草稿 1-已发布 2-已下线；学员视角该条件被忽略 */
    private Integer status;

    /** 是否只看我创建的题目（教师常用；学员传了也无效） */
    private Boolean onlyMine;
}
