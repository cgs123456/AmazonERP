package com.amz.controller;

import com.amz.interceptor.BaseAuthInterceptor;
import com.amz.mapper.UserMapper;
import com.amz.model.pojo.User;
import com.amz.service.impl.UserServiceImpl;
import com.amz.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /user/getInfo 的 effectiveRole：真实 JWT + 真实拦截器 + 真实 getInfo 实现。
 * <p>
 * <b>存在动因（P0-1，§S22.7 第 1 条 / §S24.5）</b>：店铺级角色接通后，全站前端显隐的
 * **唯一数据来源**就是这里的 {@code effectiveRole}——但在此前，它只有一条前端单测
 * （mock 掉 api/auth 之后自己喂自己），后端链路（JWT claim → 拦截器 →
 * resolveRole(请求头 shopId) → 响应字段）**没有任何一条测试跑通过真实 Web 链**。
 * 接线断在哪一层，前端都会拿到错的角色，而那条单测依然全绿。
 * <p>
 * 这里钉住的口径：
 * <ul>
 *   <li>同一个 token，请求头 shopId 指向哪家店，effectiveRole 就是那家店的角色——
 *       「店 A 是 OPERATOR、店 B 是 VIEWER」的用户切店后前端看到的角色必须跟着变；</li>
 *   <li>不带 shopId 头 → 回落全局角色（与 resolveRole 的兜底语义一致）；</li>
 *   <li>老 token（无 shop_roles claim）→ 任何店都得到全局角色——渐进发布不破；</li>
 *   <li>店铺级角色**对 ADMIN 同样生效**（店 A 配了 VIEWER 就是 VIEWER）。
 *       这是 §S22.7 第 2 条明确警告「别在下一轮误改」的点，用测试钉死。</li>
 * </ul>
 * <p>
 * 证据等级：E2（进程内，真实 MVC 链与真实拦截器，只 mock mapper）。
 */
@DisplayName("getInfo：effectiveRole 必须按请求头店铺解析（真实 JWT + 拦截器链）")
class UserInfoEffectiveRoleWebMvcIntegrationTest {

    private static final long SHOP_A = 1001L;
    private static final long SHOP_B = 1002L;

    private JwtUtil jwtUtil;
    private UserMapper userMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secretKey", "test-secret-key-for-effrole-integration");
        ReflectionTestUtils.setField(jwtUtil, "issuer", "amz-erp");
        ReflectionTestUtils.setField(jwtUtil, "audience", "amz-erp-client");
        ReflectionTestUtils.setField(jwtUtil, "expireTime", 86_400_000L);
        jwtUtil.init();

        User dbUser = new User();
        dbUser.setId(7);
        dbUser.setPhone("13800000007");
        dbUser.setNickname("多店用户");
        dbUser.setRole("OPERATOR");   // 全局角色：OPERATOR
        userMapper = Mockito.mock(UserMapper.class);
        when(userMapper.selectOne(any())).thenReturn(dbUser);

        // 真实 getInfo 实现（UserServiceImpl），只把 mapper 换成桩；
        // shopService 刻意不注入——resolveShopOptions 对 null 走空列表分支，
        // 本测试钉的是 effectiveRole，不是店铺选择器。
        UserServiceImpl userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "userMapper", userMapper);

        UserController controller = new UserController();
        ReflectionTestUtils.setField(controller, "userService", userService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new BaseAuthInterceptor(jwtUtil))
                .build();
    }

    /** 同账号：店 A 是 OPERATOR，店 B 是 VIEWER——这就是接通后要生效的场景本身。 */
    private String multiShopToken() {
        Map<Long, String> shopRoles = new LinkedHashMap<>();
        shopRoles.put(SHOP_A, "OPERATOR");
        shopRoles.put(SHOP_B, "VIEWER");
        return jwtUtil.createToken(7, List.of(SHOP_A, SHOP_B), "OPERATOR", shopRoles);
    }

    @Test
    @DisplayName("请求头指向店 A → effectiveRole=OPERATOR（该店的角色，不是全局的）")
    void effectiveRoleResolvesToShopA() throws Exception {
        mockMvc.perform(get("/user/getInfo")
                        .header("token", multiShopToken())
                        .header("shopId", String.valueOf(SHOP_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.effectiveRole").value("OPERATOR"))
                // user.role 保持全局语义不变（别处按它判跨店问题）
                .andExpect(jsonPath("$.data.user.role").value("OPERATOR"));
    }

    @Test
    @DisplayName("请求头指向店 B → effectiveRole=VIEWER（切店后前端拿到的角色必须跟着变）")
    void effectiveRoleResolvesToShopB() throws Exception {
        mockMvc.perform(get("/user/getInfo")
                        .header("token", multiShopToken())
                        .header("shopId", String.valueOf(SHOP_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.effectiveRole").value("VIEWER"));
    }

    @Test
    @DisplayName("不带 shopId 头 → 回落全局角色（与 resolveRole 兜底一致）")
    void noShopHeaderFallsBackToGlobalRole() throws Exception {
        mockMvc.perform(get("/user/getInfo")
                        .header("token", multiShopToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveRole").value("OPERATOR"));
    }

    /**
     * 渐进发布的关键一环：{@code shop_roles} 是后加的 claim，存量 token 没有它。
     * 若此时 effectiveRole 缺失或按空 map 拒绝，前端 setUserRole(effectiveRole ?? user.role)
     * 会全员退到全局角色——行为与接通前一致，这是设计使然，钉住它防「好心修复」。
     */
    @Test
    @DisplayName("老 token（无 shop_roles claim）→ 任何店都是全局角色")
    void legacyTokenWithoutShopRolesFallsBack() throws Exception {
        String legacy = jwtUtil.createToken(7, List.of(SHOP_A, SHOP_B), "OPERATOR");

        mockMvc.perform(get("/user/getInfo")
                        .header("token", legacy)
                        .header("shopId", String.valueOf(SHOP_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveRole").value("OPERATOR"));
    }

    /**
     * §S22.7 第 2 条：「别在下一轮误改成店铺级」的镜像——**判定处已经是店铺级**，
     * 全局 ADMIN 在某店被配成 VIEWER，getInfo 报的就是 VIEWER。
     * 用测试钉住这个语义，防止有人在「isShopAllowedStrict 的 ADMIN 分支」上顺手把
     * 所有店铺级判定改回全局优先。
     */
    @Test
    @DisplayName("全局 ADMIN 但店 B 配了 VIEWER → effectiveRole=VIEWER（店铺级对 ADMIN 同样生效）")
    void shopRoleGovernsEvenGlobalAdmin() throws Exception {
        Map<Long, String> shopRoles = new LinkedHashMap<>();
        shopRoles.put(SHOP_A, "ADMIN");
        shopRoles.put(SHOP_B, "VIEWER");
        String token = jwtUtil.createToken(7, List.of(SHOP_A, SHOP_B), "ADMIN", shopRoles);

        mockMvc.perform(get("/user/getInfo")
                        .header("token", token)
                        .header("shopId", String.valueOf(SHOP_B)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveRole").value("VIEWER"));
    }

    @Test
    @DisplayName("未授权店铺直接 403（拦截器按 shops claim 挡在 getInfo 之前）")
    void unauthorizedShopHeaderRejected() throws Exception {
        mockMvc.perform(get("/user/getInfo")
                        .header("token", multiShopToken())
                        .header("shopId", "9999"))
                .andExpect(status().isForbidden());
    }
}
