package com.codejudge.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户角色枚举。
 * code 与 user.type 对齐（见 sql/init.sql：类型 1员工/2学员/3教师），
 * 由登录时写入 JWT role claim，网关解析后经 role-info 头透传。
 *
 * <p>这里的 {@code *_CODE} 常量是<b>编译期常量</b>，供 {@code switch} 的 case 标签使用
 * （枚举的 {@link #getCode()} 不是常量表达式，不能直接做 case 标签）。
 * 枚举值与常量必须同源，故构造函数直接引用它们。
 * <p>引用时必须写成 {@code UserRole.STAFF_CODE} 全限定形式：JLS §8.3.3 的
 * 「非法前向引用」只约束**简单名**；而这些常量是编译期常量，会被内联，不存在初始化顺序问题。
 */
@Getter
@AllArgsConstructor
public enum UserRole {

    /** 员工（管理员） */
    STAFF(UserRole.STAFF_CODE, "admin"),
    /** 学员 */
    STUDENT(UserRole.STUDENT_CODE, "student"),
    /** 教师 */
    TEACHER(UserRole.TEACHER_CODE, "teacher");

    /** 员工（管理员）：user.type = 1 */
    public static final int STAFF_CODE = 1;
    /** 学员：user.type = 2 */
    public static final int STUDENT_CODE = 2;
    /** 教师：user.type = 3 */
    public static final int TEACHER_CODE = 3;

    private final int code;
    private final String alias;

    /** 中文展示名。由后端下发，避免前端再维护一份角色映射表 */
    public String getLabel() {
        return switch (this) {
            case STAFF -> "管理员";
            case STUDENT -> "学员";
            case TEACHER -> "教师";
        };
    }

    /** 按 user.type 取角色；未知值返回 null（fail-closed，由调用方决定如何处置） */
    public static UserRole of(Integer code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case STAFF_CODE -> STAFF;
            case STUDENT_CODE -> STUDENT;
            case TEACHER_CODE -> TEACHER;
            default -> null;
        };
    }
}
