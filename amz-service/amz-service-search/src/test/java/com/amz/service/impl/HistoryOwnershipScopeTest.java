package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.mapper.HistoryMapper;
import com.amz.model.pojo.History;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搜索历史的归属范围：**只作用于调用者自己**，且刻意不加 {@code @RequireRole}。
 * <p>
 * <b>为什么专门写这条</b>：P0 加锁那一轮用修好的 {@code endpoint_role_audit} 扫出两个
 * 「写端点但没有 {@code @RequireRole}」，本端点是被点名的其中之一。核对后结论是
 * <b>不加才对</b>——但「扫出未加锁 → 顺手加上」是最容易发生的错误动作：
 * 给 {@code DELETE /search/deleteHistory} 加 OPERATOR/ADMIN 会让 VIEWER
 * 连自己的搜索历史都清不掉，凭空造出一个不存在的权限问题。
 * <p>
 * 所以这里把判断依据钉死：作用域由 {@code userId} 决定，**与店铺和角色都无关**。
 * 谁调用就删谁的，不存在跨用户/跨租户面。将来若有人把 userId 条件去掉
 * （比如改成管理员清库），本测试会红——那正是必须重新审视权限的时点。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("搜索历史：只删自己的，不需要角色门禁")
class HistoryOwnershipScopeTest {

    @Mock
    private HistoryMapper historyMapper;

    @InjectMocks
    private HistoryServiceImpl historyService;

    /**
     * 注册 History 的 TableInfo，填充 MyBatis-Plus 的 lambda 缓存。
     * <p>
     * 没有这一步，{@code LambdaQueryWrapper.getTargetSql()} 会抛
     * {@code can not find lambda cache for this entity}——缓存由真正的 mapper
     * 初始化时才建立，单元测试里没有。照 {@code HistoryColumnContractTest} 的既有写法。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), History.class);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("删除历史按当前用户过滤，不是清空整表")
    void deleteHistoryScopesToCurrentUser() {
        UserContext.setUserId(7);
        // VIEWER 也能删自己的历史：这正是「不加 @RequireRole」的意图
        UserContext.setRole("VIEWER");

        historyService.deleteHistory();

        assertFilteredByUserId(captureWrapper(true), 7);
    }

    @Test
    @DisplayName("查询历史同样按当前用户过滤")
    void getHistoryListScopesToCurrentUser() {
        UserContext.setUserId(7);
        when(historyMapper.selectList(any())).thenReturn(List.of());

        historyService.getHistoryList();

        assertFilteredByUserId(captureWrapper(false), 7);
    }

    /**
     * 断言 wrapper 确实按当前 userId 过滤。
     * <p>
     * 只断言「SQL 里含按 user_id 的等值条件且绑定了这个 userId」，
     * 不比对整条 SQL 字符串——后者会随 MyBatis-Plus 版本漂移。
     */
    private static void assertFilteredByUserId(LambdaQueryWrapper<History> wrapper, Integer expectedUserId) {
        String sql = wrapper.getTargetSql();
        assertTrue(sql.contains("user_id"),
                "历史记录必须按 user_id 过滤；实际条件：" + sql);
        assertTrue(wrapper.getParamNameValuePairs().containsValue(expectedUserId),
                "过滤用的值必须是当前 userId=" + expectedUserId + "；实际绑定参数："
                        + wrapper.getParamNameValuePairs());
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<History> captureWrapper(boolean delete) {
        ArgumentCaptor<LambdaQueryWrapper<History>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        if (delete) {
            verify(historyMapper).delete(captor.capture());
        } else {
            verify(historyMapper).selectList(captor.capture());
        }
        return captor.getValue();
    }
}
