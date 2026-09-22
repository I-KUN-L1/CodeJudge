package com.codejudge.problem.domain.po;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.codejudge.common.domain.BasePO;
import com.codejudge.common.handler.JsonMapTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 题面版本
 *
 * <p>对应表 {@code problem_version}。题目每次被修改都会追加一条 {@code version_no + 1} 的记录，
 * {@code problem.current_version_id} 指向最新版本 —— 这样历史提交在重判时能还原当时的题面与限制。
 *
 * <p>注意 {@code autoResultMap = true} 的用途：{@link #templateCode} 是 JSON 列，
 * 查询时需由 {@link JsonMapTypeHandler} 反序列化，MyBatis-Plus 只对开启该开关的实体
 * 生成带 TypeHandler 的 resultMap（否则查出的是 null）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "problem_version", autoResultMap = true)
public class ProblemVersion extends BasePO {

    /** 题目 id */
    private Long problemId;

    /** 版本号，从 1 递增；与 problem_id 组成唯一键 */
    private Integer versionNo;

    /** 题面（Markdown） */
    private String statement;

    /** 输入说明 */
    private String inputSpec;

    /** 输出说明 */
    private String outputSpec;

    /** 提示 / 样例说明 */
    private String hint;

    /**
     * 各语言模板代码，形如 {@code {"java":"public class Main{}","python":"print()"}}。
     * <p>对应 JSON 列，需显式声明 TypeHandler（见类注释）。
     */
    @TableField(typeHandler = JsonMapTypeHandler.class)
    private Map<String, String> templateCode;

    /** 本版本修改人 id */
    private Long createdBy;
}
