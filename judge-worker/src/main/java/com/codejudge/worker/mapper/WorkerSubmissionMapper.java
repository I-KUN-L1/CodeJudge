package com.codejudge.worker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codejudge.worker.domain.po.Submission;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 提交表读写访问（worker 是判题执行的单写者）。
 * 全部 CAS：状态机只前进不回退，迟到的旧执行无法覆盖重判后的新状态。
 */
public interface WorkerSubmissionMapper extends BaseMapper<Submission> {

    /** 进入 JUDGING（认领成功后） */
    @Update("""
            UPDATE submission SET status = 'JUDGING', update_time = NOW()
            WHERE id = #{submissionId} AND status IN ('PENDING', 'JUDGING') AND deleted = 0
            """)
    int markJudging(@Param("submissionId") Long submissionId);

    /** 写终态（SUCCESS：verdict 为 AC/WA/TLE/MLE/RE/CE） */
    @Update("""
            UPDATE submission
            SET status = 'SUCCESS', verdict = #{verdict}, score = #{score},
                time_ms = #{timeMs}, memory_kb = #{memoryKb}, compile_info_id = #{compileInfoId},
                update_time = NOW()
            WHERE id = #{submissionId} AND status IN ('PENDING', 'JUDGING') AND deleted = 0
            """)
    int finishSuccess(@Param("submissionId") Long submissionId,
                      @Param("verdict") String verdict,
                      @Param("score") int score,
                      @Param("timeMs") Integer timeMs,
                      @Param("memoryKb") Integer memoryKb,
                      @Param("compileInfoId") Long compileInfoId);

    /** 写失败终态（FAILED：verdict=SE，超最大重试） */
    @Update("""
            UPDATE submission
            SET status = 'FAILED', verdict = 'SE', update_time = NOW()
            WHERE id = #{submissionId} AND status IN ('PENDING', 'JUDGING') AND deleted = 0
            """)
    int finishFailed(@Param("submissionId") Long submissionId);

    /** 重试路径：回到 PENDING 等待下一次投递 */
    @Update("""
            UPDATE submission SET status = 'PENDING', update_time = NOW()
            WHERE id = #{submissionId} AND status = 'JUDGING' AND deleted = 0
            """)
    int backToPending(@Param("submissionId") Long submissionId);
}
