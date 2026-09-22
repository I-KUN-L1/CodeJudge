package com.codejudge.problem.domain.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 题目表单（建题 / 改题共用）
 *
 * <p>字段分两组：
 * <ul>
 *   <li><b>题目主体</b>（title/difficulty/timeLimitMs/memoryLimitMb/status/tagIds）→ 落 {@code problem} 表；</li>
 *   <li><b>题面内容</b>（statement/inputSpec/outputSpec/hint/templateCode）→ 落 {@code problem_version} 表。
 *       改题时这些字段非空即追加一条新版本，空则沿用当前版本内容。</li>
 * </ul>
 * 之所以合并在一个表单里：建题时题面与主体必然同时提交，拆成两个接口会让前端多一次往返且易产生"有题无面"的中间态。
 */
@Data
public class ProblemFormDTO {

    // ==================== 题目主体（problem 表） ====================

    /** 题目标题 */
    private String title;

    /** 难度：1~5，缺省 1 */
    private Integer difficulty;

    /** 时间限制(ms)，缺省 1000 */
    private Integer timeLimitMs;

    /** 内存限制(MB)，缺省 256 */
    private Integer memoryLimitMb;

    /** 状态：0-草稿 1-已发布 2-已下线，缺省 0（建题默认草稿，避免半成品直接对学员可见） */
    private Integer status;

    /** 标签 id 列表（全量覆盖语义：传 null 表示不改动，传空数组表示清空标签） */
    private List<Long> tagIds;

    // ==================== 题面内容（problem_version 表） ====================

    /** 题面（Markdown） */
    private String statement;

    /** 输入说明 */
    private String inputSpec;

    /** 输出说明 */
    private String outputSpec;

    /** 提示 / 样例说明 */
    private String hint;

    /** 各语言模板代码，如 {"java":"public class Main{}","python":"print()"} */
    private Map<String, String> templateCode;
}
