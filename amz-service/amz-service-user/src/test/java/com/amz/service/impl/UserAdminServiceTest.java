package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.UserMapper;
import com.amz.model.pojo.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号管理（P1.4，ADMIN）三条业务规则的服务层契约。
 * <p>
 * <b>为什么需要它</b>：listUsers / updateRole / updateStatus 是「角色分配 + 启停」的后端语义，
 * 此前前端页面零覆盖、后端这三个方法也零测试——规则全靠代码自觉。
 * 本测试把四条不可回退的边界钉死（每条都能失败，不是恒真断言）：
 * <ol>
 *   <li>非 ADMIN 一律拒绝（越权即能改别人的角色/停用账号）；</li>
 *   <li>角色白名单 ADMIN/OPERATOR/VIEWER，其余点名拒绝；</li>
 *   <li>不能改自己的角色、不能禁用自己的账号（防最后一个 ADMIN 流失）；</li>
 *   <li>目标用户不存在时点名拒绝，不做静默成功。</li>
 * </ol>
 * 另钉住 {@code createTime} 透传：列表要能带出账号创建时间（DDL create_time，
 * 前端「创建时间」列此前恒为「—」的根因是 pojo 未声明该字段）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("账号管理：角色分配与启停的服务层规则")
class UserAdminServiceTest {

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void asAdmin() {
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
    }

    private User user(int id, String role) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        u.setStatus(1);
        u.setCreateTime(LocalDateTime.of(2026, 9, 1, 8, 0, 0));
        return u;
    }

    @Test
    @DisplayName("listUsers：非 ADMIN 拒绝（不能枚举全站账号）")
    void listUsersRequiresAdmin() {
        UserContext.setUserId(2);
        UserContext.setRole("OPERATOR");
        assertThrows(CodeErrorException.class, () -> userService.listUsers());
        verify(userMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("listUsers：ADMIN 拿到行，且 createTime 原样带出（前端「创建时间」列的数据源）")
    void listUsersReturnsRowsWithCreateTime() {
        asAdmin();
        List<User> rows = List.of(user(1, "ADMIN"), user(2, "VIEWER"));
        when(userMapper.selectList(any())).thenReturn(rows);

        List<User> result = userService.listUsers();

        assertEquals(2, result.size());
        assertEquals(LocalDateTime.of(2026, 9, 1, 8, 0, 0), result.get(1).getCreateTime());
    }

    @Test
    @DisplayName("updateRole：角色白名单外的值点名拒绝，不写库")
    void updateRoleRejectsUnknownRole() {
        asAdmin();
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> userService.updateRole(2, "SUPERUSER"));
        assertEquals(true, ex.getMessage().contains("ADMIN/OPERATOR/VIEWER"));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("updateRole：不能改自己的角色（防最后一个 ADMIN 自降）")
    void updateRoleRejectsSelf() {
        asAdmin();
        // 断言到具体原因，而不是「抛了某个异常」——否则「目标不存在」那条分支
        // 也能让本用例通过（变异验证实测：去掉自检后本用例仍绿 = 断言过松）
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> userService.updateRole(1, "VIEWER"));
        assertEquals(true, ex.getMessage().contains("不能修改自己的角色"));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("updateRole：目标不存在时点名拒绝，不静默成功")
    void updateRoleRejectsMissingUser() {
        asAdmin();
        when(userMapper.selectById(999)).thenReturn(null);
        assertThrows(CodeErrorException.class, () -> userService.updateRole(999, "OPERATOR"));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("updateRole：合法角色大小写归一后写库")
    void updateRoleNormalizesAndPersists() {
        asAdmin();
        User target = user(2, "VIEWER");
        when(userMapper.selectById(2)).thenReturn(target);

        User updated = userService.updateRole(2, "operator");

        assertEquals("OPERATOR", updated.getRole());
        verify(userMapper).updateById(target);
    }

    @Test
    @DisplayName("updateStatus：只接受 1/0，其余点名拒绝")
    void updateStatusRejectsInvalidValue() {
        asAdmin();
        assertThrows(CodeErrorException.class, () -> userService.updateStatus(2, 5));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("updateStatus：不能禁用自己的账号")
    void updateStatusRejectsSelf() {
        asAdmin();
        CodeErrorException ex = assertThrows(CodeErrorException.class,
                () -> userService.updateStatus(1, 0));
        assertEquals(true, ex.getMessage().contains("不能禁用自己的账号"));
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    @DisplayName("updateStatus：禁用他人写库成功")
    void updateStatusDisablesOther() {
        asAdmin();
        User target = user(3, "VIEWER");
        when(userMapper.selectById(3)).thenReturn(target);

        User updated = userService.updateStatus(3, 0);

        assertSame(target, updated);
        assertEquals(0, updated.getStatus());
        verify(userMapper).updateById(target);
    }
}
