package com.codejudge.problem.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 题目详情视图
 *
 * <p>在 {@link ProblemVO} 基础上补充题面与用例。**字段按视角分级下发**，这是本模块的核心安全边界：
 *
 * <table border="1">
 *   <caption>可见性矩阵</caption>
 *   <tr><th>字段</th><th>学员 / 其他教师</th><th>题目归属教师 / 管理员</th></tr>
 *   <tr><td>题面、输入输出说明、模板代码</td><td>✅（已发布题目）</td><td>✅</td></tr>
 *   <tr><td>{@link #samples}（is_hidden=0）</td><td>✅</td><td>✅</td></tr>
 *   <tr><td>{@link #testCases}（含 is_hidden=1）</td><td>❌ 恒为 null</td><td>✅</td></tr>
 *   <tr><td>{@link #hiddenCaseCount}</td><td>❌ 恒为 null</td><td>✅</td></tr>
 * </table>
 *
 * <p>{@code testCases} 用 null 而非空数组表达"无权限"：空数组会被误读为"这题没有隐藏用例"，
 * 从而让越权者以为拿到了完整信息。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ProblemDetailVO extends ProblemVO {

    /** 当前题面版本号 */
    private Integer versionNo;

    /** 题面（Markdown） */
    private String statement;

    /** 输入说明 */
    private String inputSpec;

    /** 输出说明 */
    private String outputSpec;

    /** 提示 / 样例说明 */
    private String hint;

    /** 各语言模板代码 */
    private Map<String, String> templateCode;

    /** 可见样例用例（is_hidden = 0），所有人可见 */
    private List<TestCaseVO> samples;

    /** 全部用例（含隐藏），**仅题目归属教师与管理员**；无权限时为 null */
    private List<TestCaseVO> testCases;

    /** 隐藏用例数量，**仅题目归属教师与管理员**；无权限时为 null */
    private Integer hiddenCaseCount;
}
