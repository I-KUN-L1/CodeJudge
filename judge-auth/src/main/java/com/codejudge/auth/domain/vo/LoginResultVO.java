package com.codejudge.auth.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

/**
 * 登录结果
 */
@Data
public class LoginResultVO {

    private String accessToken;
    private Long expireTime;

    /**
     * refresh token 只经 HttpOnly Cookie 下发，<b>不再出现在响应体中</b>（{@code @JsonIgnore}）。
     * <p>历史上它同时随 JSON body 返回，脚本与前端日志/监控都能读到 30 天有效的续签凭证，
     * 等于架空了 Cookie 的 HttpOnly 防线。字段保留是因为 Controller 需要读它写 Cookie。
     */
    @JsonIgnore
    private String refreshToken;
    private Long userId;
    private String username;

    /**
     * 用户类型（{@code user.type}：1 员工 / 2 学员 / 3 教师），由后端按账号自身属性解析得出。
     * <p>前端**不据此做任何权限判断**（那是能力码的事，见 {@link CapabilityProfileVO}），
     * 它只用于顶栏那行角色标签；标签文本用 {@link #roleLabel}，前端连映射表都不用维护。
     */
    private Integer role;

    /** 角色中文名（管理员 / 学员 / 教师） */
    private String roleLabel;

    /**
     * 登录后是否应提示「修改初始密码」。
     * <p>判据是「引导期初始凭据文件是否仍存在」—— 该文件只在首个管理员被创建、
     * 且尚未改密的窗口内存在。前端据此提示，<b>不阻断登录</b>（改密入口在登录页与个人中心）。
     */
    private Boolean mustChangePassword;
}
