package com.amz.service.impl;

import com.amz.constant.MqConstant;
import com.amz.constant.RedisConstant;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.UserMapper;
import com.amz.mapper.UserShopMapper;
import com.amz.model.dto.LoginDto;
import com.amz.model.pojo.User;
import com.amz.model.pojo.UserShop;
import com.amz.result.Result;
import com.amz.util.JwtUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录服务单元测试（纯 Mockito，不依赖 Redis / RabbitMQ 容器）。
 * <p>
 * 验证手机号校验、验证码下发、验证码校验、新用户注册、老用户登录五条核心链路。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("登录服务单元测试")
class LoginServiceImplTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private UserMapper userMapper;

    @Mock
    private UserShopMapper userShopMapper;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private LoginServiceImpl loginService;

    @Test
    @DisplayName("无效手机号 → 返回失败")
    void testSendInvalidPhone() {
        Result<String> result = loginService.send("12345");

        assertEquals(400, result.getCode(), "无效手机号应返回 400");
        assertEquals("手机号格式不正确", result.getMessage());
    }

    @Test
    @DisplayName("null 手机号 → 返回失败")
    void testSendNullPhone() {
        Result<String> result = loginService.send(null);

        assertEquals(400, result.getCode());
    }

    @Test
    @DisplayName("有效手机号 → 验证码存入 Redis 并返回成功")
    void testSendValidPhone() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        Result<String> result = loginService.send("13800138000");

        assertEquals(200, result.getCode());
        verify(valueOperations).set(
                eq(RedisConstant.PHONE_CODE.concat("13800138000")),
                anyString(),
                eq(60L),
                eq(TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("验证码错误 → 抛出 CodeErrorException")
    void testVerifyWrongCode() {
        LoginDto dto = new LoginDto();
        dto.setPhone("13800138000");
        dto.setCode("0000");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisConstant.PHONE_CODE.concat("13800138000")))
                .thenReturn("1234");

        assertThrows(CodeErrorException.class, () -> loginService.verify(dto));
    }

    @Test
    @DisplayName("验证码过期（Redis 无缓存）→ 抛出 CodeErrorException")
    void testVerifyExpiredCode() {
        LoginDto dto = new LoginDto();
        dto.setPhone("13800138000");
        dto.setCode("1234");
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisConstant.PHONE_CODE.concat("13800138000")))
                .thenReturn(null);

        assertThrows(CodeErrorException.class, () -> loginService.verify(dto));
    }

    @Test
    @DisplayName("老用户登录 → 生成 token 并发送 MQ 通知")
    void testVerifyExistingUser() {
        LoginDto dto = new LoginDto();
        dto.setPhone("13800138000");
        dto.setCode("1234");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisConstant.PHONE_CODE.concat("13800138000")))
                .thenReturn("1234");

        User user = new User();
        user.setId(1);
        user.setPhone("13800138000");
        user.setRole("ADMIN");
        when(userMapper.selectOne(any())).thenReturn(user);

        UserShop us = new UserShop();
        us.setUserId(1L);
        us.setShopId(10L);
        when(userShopMapper.selectList(any())).thenReturn(List.of(us));

        when(jwtUtil.createToken(eq(1), any(), eq("ADMIN"))).thenReturn("jwt-token");
        when(jwtUtil.createRefreshToken(eq(1))).thenReturn("refresh-jwt-token");

        Result<Map<String, String>> result = loginService.verify(dto);

        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals("jwt-token", result.getData().get("token"));
        assertEquals("refresh-jwt-token", result.getData().get("refreshToken"));
        verify(redisTemplate).delete(RedisConstant.PHONE_CODE.concat("13800138000"));
        verify(rabbitTemplate).convertAndSend(
                MqConstant.MESSAGE_NOTICE_EXCHANGE, MqConstant.LOGIN_KEY, 1);
        verify(userMapper, org.mockito.Mockito.never()).insert(any(User.class));
    }

    @Test
    @DisplayName("新用户注册 → 插入用户（默认 VIEWER 角色）并生成 token")
    void testVerifyNewUser() {
        LoginDto dto = new LoginDto();
        dto.setPhone("13900139000");
        dto.setCode("5678");

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisConstant.PHONE_CODE.concat("13900139000")))
                .thenReturn("5678");
        when(userMapper.selectOne(any())).thenReturn(null);

        doAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(2);
            return 1;
        }).when(userMapper).insert(any(User.class));

        when(userShopMapper.selectList(any())).thenReturn(List.of());
        when(jwtUtil.createToken(eq(2), any(), eq("VIEWER"))).thenReturn("new-token");
        when(jwtUtil.createRefreshToken(eq(2))).thenReturn("new-refresh-token");

        Result<Map<String, String>> result = loginService.verify(dto);

        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
        assertEquals("new-token", result.getData().get("token"));
        assertEquals("new-refresh-token", result.getData().get("refreshToken"));
        verify(userMapper).insert(any(User.class));
    }
}
