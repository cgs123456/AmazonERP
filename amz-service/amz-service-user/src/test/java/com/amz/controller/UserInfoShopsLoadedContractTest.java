package com.amz.controller;

import com.amz.interceptor.BaseAuthInterceptor;
import com.amz.mapper.UserMapper;
import com.amz.model.pojo.User;
import com.amz.model.vo.ShopVo;
import com.amz.service.ShopService;
import com.amz.service.impl.UserServiceImpl;
import com.amz.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /user/getInfo 的 {@code shopsLoaded}：查店铺失败必须与「确实没有店铺」可区分。
 * <p>
 * <b>存在动因</b>：{@code resolveShopOptions} 横跨 mybatis 映射、凭证列解密与
 * 用户-店铺绑定三处，任何一处抛异常都会冒到这里。此前的处理是 catch 后只打一句
 * warn 继续返回——于是响应里 {@code shops=null}（没查到）与 {@code shops=[]}
 * （确实没店）<b>完全同形</b>。
 * <p>
 * 这个混淆的代价是实测过的：{@code amz_shop.spapi_refresh_token} 上的
 * {@code CryptoTypeHandler} 解密失败 → selectList 整行抛异常 → 异常被吞 →
 * 前端店铺选择器空白 → 所有依赖 shopId 的接口跟着报错。
 * 而现场唯一线索是一句 warn，从「登录后没店铺」这个症状根本回溯不到解密失败。
 * <p>
 * 这里钉住的口径：
 * <ul>
 *   <li>查到店铺（含空数组）→ {@code shopsLoaded=true}；</li>
 *   <li>查询抛异常 → {@code shopsLoaded=false} 且 {@code shops} 是 null，
 *       <b>不能</b>用空数组冒充「该账号没有店铺」；</li>
 *   <li>店铺查询失败**不**否定登录态——用户信息本身查到了，
 *       响应仍为 200，否则一次 DB 抖动就把用户踢下线。</li>
 * </ul>
 * <p>
 * 证据等级：E2（进程内，真实 MVC 链与真实拦截器，只 mock mapper 与 ShopService）。
 */
@DisplayName("getInfo：shopsLoaded 必须区分「查失败」与「确实没有店铺」")
class UserInfoShopsLoadedContractTest {

    private JwtUtil jwtUtil;
    private UserMapper userMapper;
    private ShopService shopService;
    private MockMvc mockMvc;

    private static final long SHOP_ID = 9001L;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "test-secret-key-for-shops-loaded");
        ReflectionTestUtils.setField(jwtUtil, "issuer", "amz-erp");
        ReflectionTestUtils.setField(jwtUtil, "audience", "amz-erp-client");
        ReflectionTestUtils.setField(jwtUtil, "expireTime", 86_400_000L);
        jwtUtil.init();

        User dbUser = new User();
        dbUser.setId(7);
        dbUser.setPhone("{{PHONE_u4t86lr3}}");
        dbUser.setRole("ADMIN");
        userMapper = Mockito.mock(UserMapper.class);
        when(userMapper.selectOne(any())).thenReturn(dbUser);

        shopService = Mockito.mock(ShopService.class);

        UserServiceImpl userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        ReflectionTestUtils.setField(userService, "shopService", shopService);

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new BaseAuthInterceptor(jwtUtil))
                .build();
    }

    private String tokenFor(Long... shopIds) {
        return jwtUtil.createToken(7, List.of(shopIds), "ADMIN", null);
    }

    @Test
    @DisplayName("查到店铺 → shopsLoaded=true，且带回 id 与名称")
    void loadedTrueWhenQuerySucceeds() throws Exception {
        ShopVo shop = new ShopVo();
        shop.setId(SHOP_ID);
        shop.setShopName("北美站");
        when(shopService.listMyShops()).thenReturn(List.of(shop));

        mockMvc.perform(get("/user/getInfo").header("token", tokenFor(SHOP_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.shopsLoaded").value(true))
                .andExpect(jsonPath("$.data.shops.length()").value(1))
                .andExpect(jsonPath("$.data.shops[0].id").value((int) SHOP_ID))
                .andExpect(jsonPath("$.data.shops[0].name").value("北美站"));
    }

    @Test
    @DisplayName("查到但一家店都没有 → 仍是 shopsLoaded=true（这是可信的「没店」）")
    void loadedTrueWhenAccountHasNoShop() throws Exception {
        when(shopService.listMyShops()).thenReturn(List.of());

        mockMvc.perform(get("/user/getInfo").header("token", tokenFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopsLoaded").value(true))
                .andExpect(jsonPath("$.data.shops").isArray())
                .andExpect(jsonPath("$.data.shops.length()").value(0));
    }

    @Test
    @DisplayName("查询抛异常 → shopsLoaded=false 且 shops 为 null，不冒充空数组")
    void loadedFalseWhenQueryThrows() throws Exception {
        // 复现真实故障形态：凭证列解密失败从 mybatis 冒到 Service。
        when(shopService.listMyShops())
                .thenThrow(new IllegalStateException("AES-256-GCM 解密失败"));

        mockMvc.perform(get("/user/getInfo").header("token", tokenFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.shopsLoaded").value(false))
                // 关键：必须是 null 而非 []。空数组会被前端读成「该账号没有店铺」，
                // 进而清空本地列表，让后续请求带着失效 shopId 到处越权。
                .andExpect(jsonPath("$.data.shops").doesNotExist())
                // 登录态不被否定：用户信息仍在
                .andExpect(jsonPath("$.data.user.id").value(7));
    }

    @Test
    @DisplayName("ShopService 未注入（老部署形态）→ shopsLoaded=true + 空数组")
    void loadedTrueWhenShopServiceAbsent() throws Exception {
        UserServiceImpl userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);
        // 刻意不注入 shopService：对应 @Autowired(required=false) 未满足的部署。

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new BaseAuthInterceptor(jwtUtil))
                .build();

        mvc.perform(get("/user/getInfo").header("token", tokenFor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shopsLoaded").value(true))
                .andExpect(jsonPath("$.data.shops.length()").value(0));
    }

    @Test
    @DisplayName("店铺名缺失 → 回落「店铺 {id}」，不能把 null 推给前端")
    void fallsBackWhenShopNameMissing() throws Exception {
        ShopVo shop = new ShopVo();
        shop.setId(SHOP_ID);
        shop.setShopName(null);
        when(shopService.listMyShops()).thenReturn(List.of(shop));

        mockMvc.perform(get("/user/getInfo").header("token", tokenFor(SHOP_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shops[0].name").value("店铺 " + SHOP_ID));
    }
}
