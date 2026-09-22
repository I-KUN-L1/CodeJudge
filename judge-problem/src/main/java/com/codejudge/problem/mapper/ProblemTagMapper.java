package com.codejudge.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codejudge.problem.domain.po.ProblemTag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface ProblemTagMapper extends BaseMapper<ProblemTag> {

    /**
     * 物理删除某题目的全部标签关联。
     *
     * <p>⚠️ 必须物理删除：{@code uk_problem_tag (problem_id, tag_id)} **不含 deleted 列**。
     * 逻辑删除后重新给同一题打上同一标签会撞唯一键 ——
     * 表现为"取消标签后就再也打不回去了"。
     */
    @Delete("DELETE FROM problem_tag WHERE problem_id = #{problemId}")
    int deletePhysicallyByProblemId(@Param("problemId") Long problemId);

    /**
     * 物理删除某标签的全部关联（删除标签前先检查引用，避免留下孤儿关联）。
     */
    @Delete("DELETE FROM problem_tag WHERE tag_id = #{tagId}")
    int deletePhysicallyByTagId(@Param("tagId") Long tagId);
}
