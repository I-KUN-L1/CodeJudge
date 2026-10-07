package com.codejudge.submission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codejudge.submission.domain.po.CodeDraft;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 代码草稿 Mapper。
 *
 * <p>为什么 upsert / 删除不走 MyBatis-Plus 通用方法而用显式 SQL：
 * <ul>
 *   <li>{@code BasePO.deleted} 带 {@code @TableLogic}，通用 delete 会被改写成
 *       {@code UPDATE deleted=1} —— 残留行继续占用唯一键
 *       {@code uk_code_draft_user_problem(user_id, problem_id)}，
 *       种子/恢复脚本随后 INSERT IGNORE 会被静默跳过，草稿"删了就再也回不来"；</li>
 *   <li>upsert 若用「先查后插」在并发下会撞唯一键，{@code ON DUPLICATE KEY UPDATE}
 *       一步到位，顺带把历史上残留的逻辑删除行复活（deleted=0）。</li>
 * </ul>
 */
public interface CodeDraftMapper extends BaseMapper<CodeDraft> {

    /**
     * 保存或更新草稿（按 user_id + problem_id 幂等；重复保存覆盖旧内容）。
     *
     * @return 影响行数（插入 1 / 更新 2，MySQl ON DUPLICATE KEY 语义，只判非 0 即可）
     */
    @Insert("INSERT INTO code_draft (id, user_id, problem_id, language, code, create_time, update_time) "
            + "VALUES (#{id}, #{userId}, #{problemId}, #{language}, #{code}, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE deleted = 0, language = VALUES(language), "
            + "code = VALUES(code), update_time = NOW()")
    int upsert(CodeDraft draft);

    /** 物理删除本人某题的草稿（含历史逻辑删除行），保证唯一键位可复用 */
    @Delete("DELETE FROM code_draft WHERE user_id = #{userId} AND problem_id = #{problemId}")
    int physicalDelete(@Param("userId") Long userId, @Param("problemId") Long problemId);
}
