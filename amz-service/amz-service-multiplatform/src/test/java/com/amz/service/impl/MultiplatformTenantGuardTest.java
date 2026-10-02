package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.AttrIsNullException;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.OauthAppMapper;
import com.amz.mapper.PlatformAccountMapper;
import com.amz.mapper.PlatformMessageMapper;
import com.amz.mapper.PlatformProductMapper;
import com.amz.model.OauthApp;
import com.amz.model.PlatformAccount;
import com.amz.model.PlatformMessage;
import com.amz.model.PlatformProduct;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 多平台侧的跨店铺写守卫。
 *
 * <p>这一族的形状都是「先按 id 取行，再看行上的 shopId」，所以判定必须落在
 * 取出来的那一行上，且必须是严格版：
 * <ul>
 *   <li>{@code isShopAllowed} 在 userId 为空的上下文里会因为「没有授权列表」直接放行，
 *       而这些方法都是浏览器可写的动作；</li>
 *   <li>越权原来抛 {@code IllegalStateException}，会被全局兜底成 500
 *       「服务器内部错误」——被拒的一方拿不到可读结论，日志里也像系统故障；</li>
 *   <li>{@code mapProduct} 原来<b>完全没有归属判定</b>：任何登录用户都能把别人店铺的
 *       平台商品改映射到自己的 ASIN/SKU 上；</li>
 *   <li>{@code ownerShopId != null && !allowed} 这种写法把「没有归属」当成放行：
 *       {@code amz_oauth_app.owner_shop_id} 是 NOT NULL，所以那个分支只会让
 *       注册请求绕过校验，不会出现在真实数据里 —— 缺归属必须直接拒。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("多平台写守卫（店铺归属）")
class MultiplatformTenantGuardTest {

    @Mock
    private PlatformAccountMapper platformAccountMapper;

    @Mock
    private PlatformProductMapper platformProductMapper;

    @Mock
    private PlatformMessageMapper platformMessageMapper;

    @Mock
    private OauthAppMapper oauthAppMapper;

    @InjectMocks
    private MultiplatformServiceImpl service;

    @BeforeEach
    void authenticate() {
        UserContext.setUserId(7);
        UserContext.setRole("OPERATOR");
        UserContext.setShops(List.of(1L));
    }

    @AfterEach
    void clearUserContext() {
        UserContext.clear();
    }

    // ==================== 商品映射：本轮之前完全没有守卫 ====================

    @Test
    @DisplayName("映射他店平台商品时拒绝，并且不写库")
    void mapProductRejectsForeignShop() {
        when(platformProductMapper.selectById(88L)).thenReturn(product(88L, 2L));

        assertThrows(CodeErrorException.class,
                () -> service.mapProduct(88L, "B0NEW", "SKU-NEW"));
        verify(platformProductMapper, never()).updateById(any(PlatformProduct.class));
    }

    @Test
    @DisplayName("没有用户上下文的调用也不能改映射（lenient 判定会放行）")
    void mapProductRejectsContextWithoutUserId() {
        UserContext.clear();
        when(platformProductMapper.selectById(88L)).thenReturn(product(88L, 1L));

        assertThrows(CodeErrorException.class,
                () -> service.mapProduct(88L, "B0NEW", "SKU-NEW"));
        verify(platformProductMapper, never()).updateById(any(PlatformProduct.class));
    }

    @Test
    @DisplayName("映射本店商品要落库：ASIN 归一大写，SKU 只去空白；ASIN 为空则拒绝")
    void mapProductPersistsOwnShop() {
        PlatformProduct pp = product(88L, 1L);
        when(platformProductMapper.selectById(88L)).thenReturn(pp);

        assertThrows(AttrIsNullException.class,
                () -> service.mapProduct(88L, "   ", "SKU-NEW"));
        verify(platformProductMapper, never()).updateById(any(PlatformProduct.class));

        service.mapProduct(88L, " b0abc12345 ", " sku-new ");
        org.junit.jupiter.api.Assertions.assertEquals("B0ABC12345", pp.getAmazonAsin());
        org.junit.jupiter.api.Assertions.assertEquals("sku-new", pp.getAmazonSku());
        verify(platformProductMapper).updateById(pp);
    }

    @Test
    @DisplayName("商品行不存在时报缺参，不去 updateById")
    void mapProductRejectsMissingRow() {
        when(platformProductMapper.selectById(88L)).thenReturn(null);

        assertThrows(AttrIsNullException.class,
                () -> service.mapProduct(88L, "B0NEW", "SKU-NEW"));
        verifyNoInteractions(oauthAppMapper);
    }

    // ==================== 平台账号 ====================

    @Test
    @DisplayName("为无权限店铺创建账号时拒绝，落库前就挡住")
    void createAccountRejectsForeignShop() {
        PlatformAccount account = new PlatformAccount();
        account.setShopId(2L);

        assertThrows(CodeErrorException.class, () -> service.createAccount(account));
        verifyNoInteractions(platformAccountMapper);
    }

    @Test
    @DisplayName("创建账号时缺少店铺归属直接拒，而不是绕过判定")
    void createAccountRejectsMissingShop() {
        PlatformAccount account = new PlatformAccount();

        assertThrows(CodeErrorException.class, () -> service.createAccount(account));
        verifyNoInteractions(platformAccountMapper);
    }

    @Test
    @DisplayName("更新他店账号时拒绝，也不回写 shopId")
    void updateAccountRejectsForeignShop() {
        when(platformAccountMapper.selectById(9L)).thenReturn(account(9L, 2L));
        PlatformAccount patch = account(9L, 1L);

        assertThrows(CodeErrorException.class, () -> service.updateAccount(9L, patch));
        verify(platformAccountMapper, never()).updateById(any(PlatformAccount.class));
    }

    @Test
    @DisplayName("删除他店账号时拒绝")
    void deleteAccountRejectsForeignShop() {
        when(platformAccountMapper.selectById(9L)).thenReturn(account(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.deleteAccount(9L));
        verify(platformAccountMapper, never()).deleteById(9L);
    }

    @Test
    @DisplayName("测试他店账号的端点连通性被拒，且不改写账号状态")
    void testConnectionRejectsForeignShop() {
        when(platformAccountMapper.selectById(9L)).thenReturn(account(9L, 2L));

        assertThrows(CodeErrorException.class, () -> service.testConnection(9L));
        verify(platformAccountMapper, never()).updateById(any(PlatformAccount.class));
    }

    // ==================== 消息 ====================

    @Test
    @DisplayName("回复他店消息时拒绝，不写本地回复")
    void replyMessageRejectsForeignShop() {
        when(platformMessageMapper.selectById(7L)).thenReturn(message(7L, 2L));

        assertThrows(CodeErrorException.class, () -> service.replyMessage(7L, "hello"));
        verify(platformMessageMapper, never()).updateById(any(PlatformMessage.class));
    }

    @Test
    @DisplayName("分配他店消息时拒绝")
    void assignMessageRejectsForeignShop() {
        when(platformMessageMapper.selectById(7L)).thenReturn(message(7L, 2L));

        assertThrows(CodeErrorException.class, () -> service.assignMessage(7L, "kefu-2"));
        verify(platformMessageMapper, never()).updateById(any(PlatformMessage.class));
    }

    // ==================== ISV 应用 ====================

    @Test
    @DisplayName("注册没有归属店铺的应用直接拒：owner_shop_id 是 NOT NULL，绕过判定只会撞库")
    void registerAppRejectsMissingOwner() {
        OauthApp app = new OauthApp();
        app.setAppName("no-owner");

        assertThrows(AttrIsNullException.class, () -> service.registerApp(app));
        verifyNoInteractions(oauthAppMapper);
    }

    @Test
    @DisplayName("为无权限店铺注册应用时拒绝")
    void registerAppRejectsForeignOwner() {
        OauthApp app = new OauthApp();
        app.setAppName("x");
        app.setOwnerShopId(2L);

        assertThrows(CodeErrorException.class, () -> service.registerApp(app));
        verifyNoInteractions(oauthAppMapper);
    }

    @Test
    @DisplayName("轮换他店应用密钥时拒绝：那是凭证面，不能只看不归属就放行")
    void rotateAppSecretRejectsForeignOwner() {
        when(oauthAppMapper.selectById(3L)).thenReturn(app(3L, 2L));

        assertThrows(CodeErrorException.class, () -> service.rotateAppSecret(3L));
        verify(oauthAppMapper, never()).updateById(any(OauthApp.class));
    }

    @Test
    @DisplayName("轮换无归属记录的应用按 fail-closed 拒绝")
    void rotateAppSecretRejectsMissingOwner() {
        when(oauthAppMapper.selectById(3L)).thenReturn(app(3L, null));

        assertThrows(CodeErrorException.class, () -> service.rotateAppSecret(3L));
        verify(oauthAppMapper, never()).updateById(any(OauthApp.class));
    }

    @Test
    @DisplayName("ADMIN 可以轮换任意店铺的应用密钥（全局管理语义）")
    void rotateAppSecretAllowsAdmin() {
        UserContext.setRole("ADMIN");
        UserContext.setShops(List.of());
        when(oauthAppMapper.selectById(3L)).thenReturn(app(3L, 2L));

        service.rotateAppSecret(3L);
        verify(oauthAppMapper).updateById(any(OauthApp.class));
    }

    // ==================== 夹具 ====================

    // ==================== 每个入口都必须经过同一个严格判定 ====================

    /**
     * 每个 id 定位的写入口都补一条「上下文没建立」的拒绝用例：
     * 归属判定收敛到一个 helper 之后，这条链路上任何一处偷偷换回 lenient 都会在这里露出来。
     */
    @Test
    @DisplayName("无用户上下文时，账号/消息/应用三个入口一律拒绝")
    void everyWriteEntryRejectsMissingContext() {
        UserContext.clear();

        assertThrows(CodeErrorException.class,
                () -> service.createAccount(account(null, 1L)));
        when(platformAccountMapper.selectById(9L)).thenReturn(account(9L, 1L));
        assertThrows(CodeErrorException.class, () -> service.deleteAccount(9L));
        assertThrows(CodeErrorException.class, () -> service.updateAccount(9L, account(9L, 1L)));
        assertThrows(CodeErrorException.class, () -> service.testConnection(9L));
        verify(platformAccountMapper, never()).updateById(any(PlatformAccount.class));
        verify(platformAccountMapper, never()).deleteById(9L);
        verify(platformAccountMapper, never()).insert(any(PlatformAccount.class));

        when(platformMessageMapper.selectById(7L)).thenReturn(message(7L, 1L));
        assertThrows(CodeErrorException.class, () -> service.replyMessage(7L, "hi"));
        assertThrows(CodeErrorException.class, () -> service.assignMessage(7L, "kefu"));
        verify(platformMessageMapper, never()).updateById(any(PlatformMessage.class));

        when(oauthAppMapper.selectById(3L)).thenReturn(app(3L, 1L));
        assertThrows(CodeErrorException.class, () -> service.rotateAppSecret(3L));
        verify(oauthAppMapper, never()).updateById(any(OauthApp.class));
    }

    private static PlatformProduct product(Long id, Long shopId) {
        PlatformProduct pp = new PlatformProduct();
        pp.setId(id);
        pp.setShopId(shopId);
        pp.setPlatform("TEMU");
        pp.setPlatformProductId("TP-1");
        pp.setTitle("Yoga mat");
        return pp;
    }

    private static PlatformAccount account(Long id, Long shopId) {
        PlatformAccount account = new PlatformAccount();
        account.setId(id);
        account.setShopId(shopId);
        account.setPlatform("TEMU");
        account.setStatus("ACTIVE");
        return account;
    }

    private static PlatformMessage message(Long id, Long shopId) {
        PlatformMessage msg = new PlatformMessage();
        msg.setId(id);
        msg.setShopId(shopId);
        msg.setPlatform("TEMU");
        return msg;
    }

    private static OauthApp app(Long id, Long ownerShopId) {
        OauthApp app = new OauthApp();
        app.setId(id);
        app.setOwnerShopId(ownerShopId);
        app.setAppKey("AK_existing");
        return app;
    }
}
