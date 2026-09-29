package com.codejudge.user.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.codejudge.common.constants.AuthRedisKeys;
import com.codejudge.common.exceptions.BadRequestException;
import com.codejudge.user.domain.po.User;
import com.codejudge.user.mapper.UserDetailMapper;
import com.codejudge.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户状态变更单元测试：重点覆盖「禁用即时生效」——
 * 禁用账号必须写入用户级 token 吊销纪元，启用账号不得写入
 */
@ExtendWith(MockitoExtension.class)
class UserServiceUpdateStatusTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private UserDetailMapper userDetailMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private UserService userService;

    private User user(int type) {
        User user = new User();
        user.setId(100L);
        user.setType(type);
        user.setStatus(1);
        return user;
    }

    private void stubExistingUser(User user) {
        when(userMapper.selectById(100L)).thenReturn(user);
        lenient().when(userMapper.updateById(any(User.class))).thenReturn(1);
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void disablingUserWritesRevocationEpoch() {
        // 禁用即时生效：写入吊销纪元（TTL=30 天），网关与续签入口据此拒绝在途 token。
        // 学员账号（type=2）不触发「至少保留一名管理员」分支
        stubExistingUser(user(2));

        userService.updateStatus(100L, 0);

        verify(valueOps).set(
                eq(AuthRedisKeys.USER_REVOKED_BEFORE_PREFIX + 100L),
                anyString(),
                eq(Duration.ofDays(30)));
    }

    @Test
    void enablingUserDoesNotWriteRevocationEpoch() {
        // 启用（status=1）不是吊销动作，绝不能写纪元 —— 否则把用户自己的新会话也吊销了
        stubExistingUser(user(2));

        userService.updateStatus(100L, 1);

        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisFailureDoesNotBlockDisable() {
        // 吊销纪元写入失败不阻断禁用主流程（在途 token 存活至自然过期，refresh 侧另有状态校验兜底）
        stubExistingUser(user(2));
        when(redis.opsForValue()).thenReturn(valueOps);
        doThrow(new RuntimeException("connection refused"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        userService.updateStatus(100L, 0);

        verify(userMapper).updateById(any(User.class));
    }

    @Test
    void invalidStatusRejected() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> userService.updateStatus(100L, 2));
        assertEquals("状态值不合法：0-禁用 1-启用", e.getMessage());
        verify(userMapper, never()).updateById(any(User.class));
        verify(redis, never()).opsForValue();
    }
}
