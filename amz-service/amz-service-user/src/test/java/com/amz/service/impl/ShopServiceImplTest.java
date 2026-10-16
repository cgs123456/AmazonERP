package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.ShopMapper;
import com.amz.mapper.UserMapper;
import com.amz.mapper.UserShopMapper;
import com.amz.model.pojo.Shop;
import com.amz.model.pojo.User;
import com.amz.model.pojo.UserShop;
import com.amz.model.vo.ShopVo;
import com.amz.service.ShopService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 店铺主数据服务契约测试。
 * <p>
 * <b>存在动因</b>：全仓此前没有店铺端点，前端 {@code main.ts} 写死「Shop A/B/C」三个假店铺，
 * 而 axios 拦截器会把这个假 id 当 {@code shopId} 头发给网关——店铺隔离这条最重要的边界
 * 一直建立在编造数据上。本测试钉住真实店铺服务的四条关键语义：
 * <ol>
 *   <li>无绑定的普通用户拿到<b>空列表</b>，绝不返回默认店铺（否则等于给用户看别人的店）；</li>
 *   <li>非 ADMIN 只能看到自己被绑定的店（越权可见即越权可操作）；</li>
 *   <li>店铺管理接口<b>拒绝接收凭证字段</b>（凭证唯一入口是 spapi 的 /spapi/credentials）；</li>
 *   <li>分配/解绑的角色与入参校验按白名单，非法值点名拒绝。</li>
 * </ol>
 * 变异验证（写本测试时实际注入确认会红）：把 listMyShops 的空绑定分支改成「返回全部店铺」
 * → 第一例红；去掉 requireAdmin → 越权两例红；放行凭证字段 → 凭证例红；
 * 把角色白名单改成「任意字符串都接受」→ 角色例红。
 */
class ShopServiceImplTest {

    /**
     * MyBatis-Plus 3.5.17 起 lambda cache 由 mapper 注册时初始化，单测 mock 掉 mapper 后
     * 无人初始化，{@code LambdaQueryWrapper.eq(Shop::getId, ...)} 会抛
     * 「can not find lambda cache」。手动注册一次（仓内既有先例：
     * AdTenantIsolationContractTest.register / MultiplatformServiceImplTest）。
     */
    @org.junit.jupiter.api.BeforeAll
    static void registerTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                Shop.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                UserShop.class);
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    @DisplayName("店铺：无绑定的普通用户返回空列表，不给默认店铺")
    void userWithoutBindingSeesNothing() {
        ShopServiceImpl service = serviceWith(mock(ShopMapper.class), mock(UserShopMapper.class));
        UserContext.setUserId(42);
        UserContext.setRole("VIEWER");
        when(serviceMapper(service).selectList(any())).thenReturn(List.of());
        when(shopMapper(service).selectList(any())).thenReturn(List.of(shop(1L, "A"), shop(2L, "B")));

        List<ShopVo> shops = service.listMyShops();

        assertTrue(shops.isEmpty(),
                "没被授权任何店铺时必须返回空——返回默认店铺等于让用户看到不属于他的店");
        verify(shopMapper(service), never()).selectList(any());
    }

    @Test
    @DisplayName("店铺：普通用户只看到被绑定的店，且带店铺级角色")
    void userSeesOnlyBoundShops() {
        ShopMapper shops = mock(ShopMapper.class);
        UserShopMapper bindings = mock(UserShopMapper.class);
        ShopServiceImpl service = serviceWith(shops, bindings);

        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserShop binding = new UserShop();
        binding.setShopId(9002L);
        binding.setUserId(7L);
        binding.setRole("VIEWER");
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        when(shops.selectList(any())).thenReturn(List.of(shop(9002L, "UK 店")));

        List<ShopVo> result = service.listMyShops();

        assertEquals(1, result.size());
        assertEquals(9002L, result.get(0).getId());
        assertEquals("VIEWER", result.get(0).getRole(),
                "店铺级角色必须来自绑定表：同一用户在不同店可以有不同权限");
    }

    @Test
    @DisplayName("店铺：ADMIN 可见全部店铺（否则无法给别人分店）")
    void adminSeesAllShops() {
        ShopMapper shops = mock(ShopMapper.class);
        ShopServiceImpl service = serviceWith(shops, mock(UserShopMapper.class));
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        when(shops.selectList(any())).thenReturn(List.of(shop(1L, "A"), shop(2L, "B")));

        assertEquals(2, service.listMyShops().size());
        assertEquals("ADMIN", service.listMyShops().get(0).getRole());
    }

    @Test
    @DisplayName("店铺：非 ADMIN 写店铺被拒绝")
    void nonAdminCannotManageShops() {
        ShopServiceImpl service = serviceWith(mock(ShopMapper.class), mock(UserShopMapper.class));
        UserContext.setUserId(9);
        UserContext.setRole("OPERATOR");

        Shop shop = shop(1L, "X");
        assertThrows(CodeErrorException.class, () -> service.createShop(shop));
        assertThrows(CodeErrorException.class, () -> service.updateShop(1L, shop));
        assertThrows(CodeErrorException.class, () -> service.updateShopStatus(1L, 1));
        assertThrows(CodeErrorException.class, () -> service.assignUser(1L, 2L, "VIEWER"));
        assertThrows(CodeErrorException.class, () -> service.unassignUser(1L, 2L));
    }

    @Test
    @DisplayName("店铺：店级 ADMIN 能管本店，不能管别家店（店铺级角色接通 §S22）")
    void shopLevelAdminIsScopedToOwnShop() {
        ShopMapper shops = mock(ShopMapper.class);
        ShopServiceImpl service = serviceWith(shops, mock(UserShopMapper.class));
        UserContext.setUserId(1);
        // 全局只是 OPERATOR，但在店 1 是 ADMIN、店 2 是 VIEWER
        UserContext.setRole("OPERATOR");
        UserContext.setShopRoles(java.util.Map.of(1L, "ADMIN", 2L, "VIEWER"));
        Shop patch = shop(1L, "改了名");
        when(shops.selectById(1L)).thenReturn(shop(1L, "A"));

        // 本店是 ADMIN → 通过
        assertEquals("改了名", service.updateShop(1L, patch).getShopName());

        // 别家店只给了 VIEWER → 拒绝
        assertThrows(CodeErrorException.class, () -> service.updateShop(2L, patch));
        assertThrows(CodeErrorException.class, () -> service.updateShopStatus(2L, 1));
        assertThrows(CodeErrorException.class, () -> service.assignUser(2L, 5L, "VIEWER"));
        assertThrows(CodeErrorException.class, () -> service.unassignUser(2L, 5L));
        assertThrows(CodeErrorException.class, () -> service.listShopUsers(2L));
    }

    @Test
    @DisplayName("店铺：建店要求**全局** ADMIN，店级 ADMIN 不够（新店不属于任何现有店铺）")
    void createShopRequiresGlobalAdmin() {
        ShopServiceImpl service = serviceWith(mock(ShopMapper.class), mock(UserShopMapper.class));
        UserContext.setUserId(1);
        UserContext.setRole("VIEWER");
        // 只在某一家店是 ADMIN —— 不得因此获得建店（跨店）能力
        UserContext.setShopRoles(java.util.Map.of(1L, "ADMIN"));

        assertThrows(CodeErrorException.class, () -> service.createShop(shop(9L, "新店")));
    }

    @Test
    @DisplayName("店铺：全局 ADMIN 不受店铺级收窄影响（跨店管理语义保留）")
    void globalAdminKeepsCrossShopManagement() {
        ShopMapper shops = mock(ShopMapper.class);
        ShopServiceImpl service = serviceWith(shops, mock(UserShopMapper.class));
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        // 即使在店 2 被显式配成 VIEWER，全局 ADMIN 仍可建店（跨店动作）
        UserContext.setShopRoles(java.util.Map.of(2L, "VIEWER"));
        when(shops.selectById(1L)).thenReturn(shop(1L, "A"));

        assertNotNull(service.createShop(shop(9L, "新店")));
    }

    @Test
    @DisplayName("店铺：拒绝接收凭证字段（唯一入口是 spapi 的 /spapi/credentials）")
    void rejectsCredentialFields() {
        ShopServiceImpl service = serviceWith(mock(ShopMapper.class), mock(UserShopMapper.class));
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");

        Shop withSecret = new Shop();
        withSecret.setShopName("店");
        withSecret.setMarketplaceId("ATVPDKIKX0DER");
        withSecret.setRegion("NA");
        withSecret.setSpapiClientSecret("secret-value");

        CodeErrorException e = assertThrows(CodeErrorException.class, () -> service.createShop(withSecret));
        assertTrue(e.getMessage().contains("凭证"),
                "拒绝理由必须点名「凭证」，实际：" + e.getMessage());

        Shop withToken = new Shop();
        withToken.setShopName("店");
        withToken.setMarketplaceId("ATVPDKIKX0DER");
        withToken.setRegion("NA");
        withToken.setSpapiRefreshToken("refresh-value");
        assertThrows(CodeErrorException.class, () -> service.createShop(withToken),
                "amz_shop 的凭证列已废弃（事实源是 amz_shop_credential），这里不得再造一条写入路径");
    }

    @Test
    @DisplayName("店铺：region 只接受 NA/EU/FE，且归一化大写")
    void validatesRegion() {
        ShopMapper mapper = mock(ShopMapper.class);
        ShopServiceImpl service = serviceWith(mapper, mock(UserShopMapper.class));
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");

        Shop bad = new Shop();
        bad.setShopName("店");
        bad.setMarketplaceId("ATVPDKIKX0DER");
        bad.setRegion("MARS");
        assertThrows(CodeErrorException.class, () -> service.createShop(bad));

        Shop ok = new Shop();
        ok.setShopName("店");
        ok.setMarketplaceId("ATVPDKIKX0DER");
        ok.setRegion(" na ");
        when(mapper.insert(any(Shop.class))).thenReturn(1);
        service.createShop(ok);
        assertEquals("NA", ok.getRegion(), "region 必须归一化，否则同一区域会出现多种写法");
    }

    @Test
    @DisplayName("店铺：分配角色按白名单，非法值点名拒绝")
    void validatesShopRole() {
        ShopMapper shops = mock(ShopMapper.class);
        UserShopMapper bindings = mock(UserShopMapper.class);
        UserMapper users = mock(UserMapper.class);
        ShopServiceImpl service = serviceWith(shops, bindings);
        ReflectionTestUtils.setField(service, "userMapper", users);

        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        when(shops.selectById(9001L)).thenReturn(shop(9001L, "A"));
        when(users.selectById(5)).thenReturn(new User());
        when(bindings.selectOne(any())).thenReturn(null);

        assertThrows(CodeErrorException.class, () -> service.assignUser(9001L, 5L, "SUPERUSER"));

        service.assignUser(9001L, 5L, "operator");
        verify(bindings).insert(any(UserShop.class));
    }

    @Test
    @DisplayName("店铺：解绑未绑定的用户明确失败，不静默成功")
    void unassignRejectsNonExistingBinding() {
        ShopServiceImpl service = serviceWith(mock(ShopMapper.class), mock(UserShopMapper.class));
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");

        assertThrows(CodeErrorException.class, () -> service.unassignUser(9001L, 5L),
                "未绑定却返回成功会让管理员以为回收生效了");
    }

    @Test
    @DisplayName("店铺：状态只接受 1/0/-1")
    void validatesStatus() {
        ShopServiceImpl service = serviceWith(mock(ShopMapper.class), mock(UserShopMapper.class));
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");

        assertThrows(CodeErrorException.class, () -> service.updateShopStatus(9001L, 99));
    }

    @Test
    @DisplayName("店铺：凭证状态查询失败时按未配置处理，不阻断列表")
    void credentialQueryFailureDoesNotBreakList() {
        ShopMapper shops = mock(ShopMapper.class);
        ShopServiceImpl service = serviceWith(shops, mock(UserShopMapper.class));
        // jdbcTemplate 保持 null（注入失败/未配置）：hasCredential 必须降级为 false
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        when(shops.selectList(any())).thenReturn(List.of(shop(9001L, "A")));

        List<ShopVo> result = service.listMyShops();

        assertEquals(1, result.size());
        assertFalse(result.get(0).isCredentialConfigured(),
                "查询不了凭证状态时应按未配置处理——谎报已配置会让运营跳过真正需要的配置步骤");
        assertNotNull(result.get(0).getStatusText());
    }

    @Test
    @DisplayName("店铺：列出某店铺已绑定用户（含店铺级角色）——授权界面不能盲写")
    void listShopUsersReturnsBindings() {
        ShopMapper shops = mock(ShopMapper.class);
        UserShopMapper bindings = mock(UserShopMapper.class);
        UserMapper users = mock(UserMapper.class);
        ShopServiceImpl service = serviceWith(shops, bindings, users);

        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        when(shops.selectById(9001L)).thenReturn(shop(9001L, "US 店"));

        UserShop b1 = new UserShop();
        b1.setShopId(9001L);
        b1.setUserId(7L);
        b1.setRole("OPERATOR");
        UserShop b2 = new UserShop();
        b2.setShopId(9001L);
        b2.setUserId(8L);
        b2.setRole("VIEWER");
        when(bindings.selectList(any())).thenReturn(List.of(b1, b2));

        User u7 = new User();
        u7.setId(7);
        u7.setNickname("运营甲");
        u7.setPhone("13800000077");
        User u8 = new User();
        u8.setId(8);
        u8.setNickname("访客乙");
        u8.setPhone("13800000088");
        when(users.selectById(7)).thenReturn(u7);
        when(users.selectById(8)).thenReturn(u8);

        List<com.amz.model.vo.ShopUserVo> result = service.listShopUsers(9001L);

        assertEquals(2, result.size());
        assertEquals(7L, result.get(0).getUserId());
        assertEquals("运营甲", result.get(0).getNickname());
        assertEquals("OPERATOR", result.get(0).getRole());
        assertEquals("VIEWER", result.get(1).getRole());
    }

    @Test
    @DisplayName("店铺：列出绑定用户对不存在的店铺点名拒绝，不返回空列表冒充「无人绑定」")
    void listShopUsersRejectsMissingShop() {
        ShopMapper shops = mock(ShopMapper.class);
        ShopServiceImpl service = serviceWith(shops, mock(UserShopMapper.class), mock(UserMapper.class));
        UserContext.setUserId(1);
        UserContext.setRole("ADMIN");
        when(shops.selectById(9999L)).thenReturn(null);

        assertThrows(CodeErrorException.class, () -> service.listShopUsers(9999L),
                "店铺不存在时必须报错——返回空会让管理员以为这家店确实没人被授权");
    }

    @Test
    @DisplayName("店铺：列出绑定用户限定 ADMIN（不能枚举他人店铺的用户清单）")
    void listShopUsersRequiresAdmin() {
        ShopServiceImpl service = serviceWith(mock(ShopMapper.class), mock(UserShopMapper.class),
                mock(UserMapper.class));
        UserContext.setUserId(9);
        UserContext.setRole("OPERATOR");

        assertThrows(CodeErrorException.class, () -> service.listShopUsers(9001L));
    }

    // ============ 辅助 ============

    private ShopServiceImpl serviceWith(ShopMapper shops, UserShopMapper bindings) {
        return serviceWith(shops, bindings, mock(UserMapper.class));
    }

    private ShopServiceImpl serviceWith(ShopMapper shops, UserShopMapper bindings, UserMapper users) {
        ShopServiceImpl service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "shopMapper", shops);
        ReflectionTestUtils.setField(service, "userShopMapper", bindings);
        ReflectionTestUtils.setField(service, "userMapper", users);
        return service;
    }

    private ShopMapper shopMapper(ShopServiceImpl service) {
        return (ShopMapper) ReflectionTestUtils.getField(service, "shopMapper");
    }

    private UserShopMapper serviceMapper(ShopServiceImpl service) {
        return (UserShopMapper) ReflectionTestUtils.getField(service, "userShopMapper");
    }

    private Shop shop(Long id, String name) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setShopName(name);
        shop.setMarketplaceId("ATVPDKIKX0DER");
        shop.setRegion("NA");
        shop.setStatus(1);
        return shop;
    }
}
