package com.amz.service.impl;

import com.amz.mapper.UserMapper;
import com.amz.model.pojo.User;
import com.amz.result.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * 用户服务单元测试（纯 Mockito，不依赖数据库）。
 * <p>
 * 回归：getUserById 对不存在用户曾返回 success(null)，调用方易 NPE；
 * 现明确返回 failure。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户服务单元测试")
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    @DisplayName("getUserById：用户存在时返回成功")
    void getUserByIdExists() {
        User user = new User();
        user.setId(1);
        when(userMapper.selectById(1)).thenReturn(user);

        Result<User> result = userService.getUserById(1);
        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
    }

    @Test
    @DisplayName("getUserById：用户不存在时返回失败而非 success(null)")
    void getUserByIdMissingFails() {
        when(userMapper.selectById(999)).thenReturn(null);

        Result<User> result = userService.getUserById(999);
        assertEquals(400, result.getCode());
    }
}
