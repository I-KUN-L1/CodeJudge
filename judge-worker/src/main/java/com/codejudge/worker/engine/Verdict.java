package com.codejudge.worker.engine;

import lombok.Getter;

/**
 * 判题结论（六种业务结论 + 一种平台结论）。
 *
 * <p>严重度序（同提交多用例时首个非 AC 用例决定提交结论）：
 * {@code SE > CE > RE > MLE > TLE > WA > AC}。
 * CE 在 worker 流程中提前短路（编译失败不会跑用例），此序仅用于表述。
 */
@Getter
public enum Verdict {

    /** Accepted：全部用例通过 */
    AC("通过"),
    /** Wrong Answer：输出与期望不符 */
    WA("答案错误"),
    /** Time Limit Exceeded：超时（内层 timeout 触发或实测耗时超限） */
    TLE("超出时间限制"),
    /** Memory Limit Exceeded：超内存（cgroup OOM 或内存峰值超限） */
    MLE("超出内存限制"),
    /** Runtime Error：非零退出码（先于 WA 判定） */
    RE("运行时错误"),
    /** Compile Error：编译失败（仅编译型语言），stderr 落 compile_info */
    CE("编译错误"),
    /** System Error：沙箱/平台自身故障，与用户代码无关，走重试/死信 */
    SE("系统错误");

    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    /** 平台侧结论（SE）不应与用户代码结论混排，单独判断 */
    public boolean isSystemError() {
        return this == SE;
    }
}
