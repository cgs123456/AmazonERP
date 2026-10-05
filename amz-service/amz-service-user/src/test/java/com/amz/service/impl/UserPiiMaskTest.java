package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.UserMapper;
import com.amz.model.pojo.User;
import com.amz.result.Result;
import com.amz.util.OssUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * /user/getUserById 的 PII 边界测试。
 * <p>
 * 该端点被搜索服务用于商品卡片展示卖家昵称，不能整体关掉；
 * 但此前它把 phone/address 原样返回，枚举 userId 就能拖走全站用户联系方式。
 * 契约：非本人、非 ADMIN 一律掩码 phone/address；本人与 ADMIN 拿全量。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("getUserById PII 掩码：非本人/非 ADMIN 只能看到展示字段")
class UserPiiMaskTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private OssUtil ossUtil;

    @InjectMocks
    private UserServiceImpl userService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private User user(int id) {
        User u = new User();
        u.setId(id);
        u.setNickname("卖家A");
        u.setPhone("13800000000");
        u.setAddress("杭州市某街道");
        return u;
    }

    @Test
    @DisplayName("本人查询：phone/address 原样返回")
    void selfSeesOwnPii() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        when(userMapper.selectById(7)).thenReturn(user(7));

        Result<User> result = userService.getUserById(7);

        assertEquals(200, result.getCode());
        assertEquals("13800000000", result.getData().getPhone());
        assertEquals("杭州市某街道", result.getData().getAddress());
    }

    @Test
    @DisplayName("他人查询（非 ADMIN）：phone/address 掩码，昵称保留供卖家卡片展示")
    void otherUserGetsMaskedPii() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        when(userMapper.selectById(8)).thenReturn(user(8));

        Result<User> result = userService.getUserById(8);

        assertEquals(200, result.getCode());
        assertNotNull(result.getData().getNickname());
        assertNull(result.getData().getPhone());
        assertNull(result.getData().getAddress());
    }

    @Test
    @DisplayName("ADMIN 查询他人：全量返回")
    void adminSeesFullPii() {
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        when(userMapper.selectById(8)).thenReturn(user(8));

        Result<User> result = userService.getUserById(8);

        assertEquals("13800000000", result.getData().getPhone());
        assertEquals("杭州市某街道", result.getData().getAddress());
    }

    @Test
    @DisplayName("无用户上下文（服务令牌调用）：默认掩码，宁可少给不泄 PII")
    void anonymousContextGetsMaskedPii() {
        when(userMapper.selectById(8)).thenReturn(user(8));

        Result<User> result = userService.getUserById(8);

        assertNull(result.getData().getPhone());
        assertNull(result.getData().getAddress());
    }
}
