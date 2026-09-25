package com.codejudge.auth.domain;

import com.codejudge.common.constants.UserRole;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 能力码登记表测试 —— 把「谁是权限的唯一权威」这件事钉在编译+测试上。
 *
 * <p>这些断言的价值不在覆盖率，而在<b>防回归</b>：
 * <ul>
 *   <li>{@link #unknownUserTypeGetsNothing()} 保证 {@code user.type} 出现脏值/
 *       未来新增角色时不会"默认给满"（把管理面按钮画给学员）；</li>
 *   <li>{@link #everyGrantedCodeIsDeclaredInCatalogue()} 保证"授权集合里写了一个码、
 *       却忘了登记到目录"这种低级错误立刻红 —— 否则接口不下发该码，
 *       前端按钮永远不出现，且没有任何报错（静默失效）。</li>
 * </ul>
 */
class CapabilitiesTest {

    @Test
    void nullUserTypeGetsNothing() {
        assertTrue(Capabilities.of(null).isEmpty(), "未登录/类型缺失必须给空集");
    }

    @Test
    void unknownUserTypeGetsNothing() {
        // fail-closed：拿不准就不给任何按钮，而不是给满
        for (Integer dirty : new Integer[]{0, 4, 9, -1, Integer.MAX_VALUE}) {
            assertTrue(Capabilities.of(dirty).isEmpty(), "未知 user.type=" + dirty + " 必须为空集");
        }
    }

    @Test
    void rolesAreNestedStudentTeacherStaff() {
        Set<String> student = Capabilities.of(UserRole.STUDENT_CODE);
        Set<String> teacher = Capabilities.of(UserRole.TEACHER_CODE);
        Set<String> staff = Capabilities.of(UserRole.STAFF_CODE);

        // 教师必须在学员之上、员工必须在教师之上：任何"教师反而少一个学员能力"都是配置笔误
        assertTrue(teacher.containsAll(student), "教师能力必须包含学员能力");
        assertTrue(staff.containsAll(teacher), "员工能力必须包含教师能力");
        assertFalse(student.containsAll(teacher), "学员不应拥有教师能力（否则嵌套断言无意义）");
    }

    @Test
    void studentCannotReachTeachingOrSystemCapabilities() {
        Set<String> student = Capabilities.of(UserRole.STUDENT_CODE);
        assertFalse(student.contains(Capabilities.PROBLEM_CREATE));
        assertFalse(student.contains(Capabilities.PROBLEM_EDIT));
        assertFalse(student.contains(Capabilities.PROBLEM_TESTCASE));
        assertFalse(student.contains(Capabilities.CONTEST_CREATE));
        assertFalse(student.contains(Capabilities.SUBMISSION_VIEW_ALL));
        assertFalse(student.contains(Capabilities.SUBMISSION_REJUDGE));
        assertFalse(student.contains(Capabilities.SUBMISSION_HIDDEN_OUTPUT));
        assertFalse(student.contains(Capabilities.KNOWLEDGE_MANAGE));
        assertFalse(student.contains(Capabilities.USER_MANAGE));
        assertFalse(student.contains(Capabilities.MONITOR_VIEW));
        // 而学员**应该**有的那几个（防止把集合写空导致"全部按钮消失"却测试仍绿）
        assertTrue(student.contains(Capabilities.PROBLEM_VIEW));
        assertTrue(student.contains(Capabilities.SUBMISSION_CREATE));
        assertEquals(6, student.size(), "学员能力码数量变化时请同步前端断言与文档");
    }

    @Test
    void teacherGetsTeachingButNotSystem() {
        Set<String> teacher = Capabilities.of(UserRole.TEACHER_CODE);
        assertTrue(teacher.contains(Capabilities.PROBLEM_CREATE));
        assertTrue(teacher.contains(Capabilities.PROBLEM_TESTCASE));
        assertTrue(teacher.contains(Capabilities.SUBMISSION_VIEW_ALL));
        assertTrue(teacher.contains(Capabilities.KNOWLEDGE_MANAGE));
        assertFalse(teacher.contains(Capabilities.USER_MANAGE));
        assertFalse(teacher.contains(Capabilities.TAG_MANAGE));
        assertFalse(teacher.contains(Capabilities.WORKER_VIEW));
        assertFalse(teacher.contains(Capabilities.MONITOR_VIEW));
    }

    @Test
    void staffGetsWholeCatalogue() {
        // 员工用 CATALOGUE 的键集，新增能力码时自动纳入 —— 断言它确实等价
        assertEquals(Capabilities.catalogue().keySet(), Capabilities.of(UserRole.STAFF_CODE));
    }

    @Test
    void everyGrantedCodeIsDeclaredInCatalogue() {
        for (Integer type : new Integer[]{UserRole.STAFF_CODE, UserRole.STUDENT_CODE, UserRole.TEACHER_CODE}) {
            for (String code : Capabilities.of(type)) {
                assertTrue(Capabilities.catalogue().containsKey(code),
                        "能力码 " + code + " 已授权给 user.type=" + type
                                + " 但未登记到 CATALOGUE —— 接口不会下发它，前端按钮将永远不出现且无任何报错");
            }
        }
    }

    @Test
    void catalogueEntriesAreWellFormed() {
        assertFalse(Capabilities.catalogue().isEmpty());
        Capabilities.catalogue().forEach((code, name) -> {
            assertNotNull(code);
            assertTrue(code.contains(":"), "能力码应形如 域:动作，实际=" + code);
            assertFalse(name == null || name.isBlank(), "能力码 " + code + " 缺少展示名");
        });
    }

    @Test
    void ofIsIdempotentAndUnmodifiable() {
        Set<String> first = Capabilities.of(UserRole.STUDENT_CODE);
        assertEquals(first, Capabilities.of(UserRole.STUDENT_CODE));
        // 调用方拿到的是不可变集合：防止某处顺手 add 污染全局授权表
        assertTrue(assertThrowsUnsupported(() -> first.add("hack:all")), "授权集合必须不可变");
    }

    private boolean assertThrowsUnsupported(Runnable action) {
        try {
            action.run();
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }
}
