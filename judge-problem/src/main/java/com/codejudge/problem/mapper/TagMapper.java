package com.codejudge.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codejudge.problem.domain.po.Tag;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface TagMapper extends BaseMapper<Tag> {

    /**
     * 物理删除标签。
     *
     * <p>必须物理删除：{@code uk_tag_name (name)} **不含 deleted 列**。
     * 逻辑删除后管理员再新建同名标签会撞唯一键，前端只能看到"数据已存在"却找不到已删的那条。
     */
    @Delete("DELETE FROM tag WHERE id = #{id}")
    int deletePhysicallyById(@Param("id") Long id);
}
