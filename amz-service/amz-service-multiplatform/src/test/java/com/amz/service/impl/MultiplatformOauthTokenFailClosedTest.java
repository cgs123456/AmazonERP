package com.amz.service.impl;

import com.amz.exception.AttrIsNullException;
import com.amz.mapper.OauthAppMapper;
import com.amz.model.OauthApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * OAuth Token 签发的 fail-closed 分支契约（B 桶补测，2026-10-08）。
 * <p>
 * {@code generateToken} 已实现全部拒绝分支，但此前只有「缺 appKey/shopId」被断言
 * （{@code MultiplatformServiceImplTest}）——App 不存在、密钥不匹配、归属店铺不符、
 * scope 交集为空这四条「凭 appKey 或越权就想换 token」的路径无测试。这些正是安全边界：
 * 漏掉一条，持他人 appKey 或跨店 shopId 就能签出 token。补断言把它们钉住。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OAuth Token 签发 fail-closed 契约")
class MultiplatformOauthTokenFailClosedTest {

    private static final String APP_KEY = "ak-demo";
    private static final String APP_SECRET = "sk-demo-secret";
    private static final long OWNER_SHOP = 1L;

    @Mock
    private OauthAppMapper oauthAppMapper;

    @InjectMocks
    private MultiplatformServiceImpl service;

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private OauthApp app(String status, String encryptedSecret, String scopes, Long ownerShopId) {
        OauthApp app = new OauthApp();
        app.setAppKey(APP_KEY);
        app.setStatus(status);
        app.setAppSecretEncrypted(encryptedSecret);
        app.setScopes(scopes);
        app.setOwnerShopId(ownerShopId);
        return app;
    }

    @Test
    @DisplayName("App 不存在 → 拒绝（不得凭未知 appKey 签发）")
    void unknownAppRejected() {
        when(oauthAppMapper.selectOne(any())).thenReturn(null);

        AttrIsNullException ex = assertThrows(AttrIsNullException.class,
                () -> service.generateToken(APP_KEY, APP_SECRET, null, OWNER_SHOP));
        assertEquals("OAuth App 不存在或已停用", ex.getMessage());
    }

    @Test
    @DisplayName("App 已停用 → 拒绝（停用应用不得继续换 token）")
    void inactiveAppRejected() {
        when(oauthAppMapper.selectOne(any()))
                .thenReturn(app("REVOKED", sha256Hex(APP_SECRET), "order.read", OWNER_SHOP));

        assertThrows(AttrIsNullException.class,
                () -> service.generateToken(APP_KEY, APP_SECRET, null, OWNER_SHOP));
    }

    @Test
    @DisplayName("密钥不匹配 → 拒绝（仅凭 appKey 不得签发）")
    void wrongSecretRejected() {
        when(oauthAppMapper.selectOne(any()))
                .thenReturn(app("ACTIVE", sha256Hex(APP_SECRET), "order.read", OWNER_SHOP));

        AttrIsNullException ex = assertThrows(AttrIsNullException.class,
                () -> service.generateToken(APP_KEY, "wrong-secret", null, OWNER_SHOP));
        assertEquals("应用密钥不正确或未初始化，请先轮换密钥", ex.getMessage());
    }

    @Test
    @DisplayName("历史行密钥未初始化（加密列为空）→ 拒绝并指引轮换")
    void uninitializedSecretRejected() {
        when(oauthAppMapper.selectOne(any()))
                .thenReturn(app("ACTIVE", null, "order.read", OWNER_SHOP));

        assertThrows(AttrIsNullException.class,
                () -> service.generateToken(APP_KEY, APP_SECRET, null, OWNER_SHOP));
    }

    @Test
    @DisplayName("shopId 非应用归属店铺 → 拒绝（防持单应用凭证跨店签发）")
    void foreignShopRejected() {
        when(oauthAppMapper.selectOne(any()))
                .thenReturn(app("ACTIVE", sha256Hex(APP_SECRET), "order.read", OWNER_SHOP));

        assertThrows(IllegalStateException.class,
                () -> service.generateToken(APP_KEY, APP_SECRET, null, 999L));
    }

    @Test
    @DisplayName("请求 scopes 与应用授权交集为空 → 拒绝（不得签出空权限 token）")
    void emptyScopeIntersectionRejected() {
        when(oauthAppMapper.selectOne(any()))
                .thenReturn(app("ACTIVE", sha256Hex(APP_SECRET), "order.read", OWNER_SHOP));

        AttrIsNullException ex = assertThrows(AttrIsNullException.class,
                () -> service.generateToken(APP_KEY, APP_SECRET,
                        new String[]{"inventory.write"}, OWNER_SHOP));
        assertEquals("无有效授权范围", ex.getMessage());
    }
}
