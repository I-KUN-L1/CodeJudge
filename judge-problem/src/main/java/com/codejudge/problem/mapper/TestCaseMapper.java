package com.codejudge.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codejudge.problem.domain.po.TestCase;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface TestCaseMapper extends BaseMapper<TestCase> {

    /**
     * 物理删除某题目的全部用例。
     *
     * <p>必须物理删除，不能走 {@code BaseMapper#delete} 的逻辑删除：
     * {@code test_case} 的唯一键是 {@code uk_test_case_seq (problem_id, seq)}，**不含 deleted 列**。
     * 逻辑删除只是把 deleted 置 1 而保留行，之后再用同一个 seq 插入用例就会撞唯一键，
     * 表现为"删了用例却再也加不回同样的序号"。
     */
    @Delete("DELETE FROM test_case WHERE problem_id = #{problemId}")
    int deletePhysicallyByProblemId(@Param("problemId") Long problemId);

    /**
     * 物理删除单个用例（理由同上：允许 seq 被后续新增复用）。
     */
    @Delete("DELETE FROM test_case WHERE id = #{id}")
    int deletePhysicallyById(@Param("id") Long id);
}
