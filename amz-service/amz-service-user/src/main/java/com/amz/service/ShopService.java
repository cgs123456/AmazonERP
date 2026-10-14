package com.amz.service;

import com.amz.model.pojo.Shop;
import com.amz.model.vo.ShopUserVo;
import com.amz.model.vo.ShopVo;

import java.util.List;

/**
 * 店铺主数据服务。
 * <p>
 * 读：列表按 {@code amz_user_shop} 归属过滤（ADMIN 可见全部）。
 * 写：创建/更新/停启用/用户-店铺分配，全部限定 ADMIN。
 *
 * @see com.amz.service.impl.ShopServiceImpl
 */
public interface ShopService {

    /**
     * 当前登录用户可见的店铺列表。
     * <p>
     * 非 ADMIN 且无任何绑定时返回<b>空列表</b>，不返回默认店铺——
     * 「没授权」和「有授权但列表为空」是两件事，混同会让用户以为看到了全部数据。
     */
    List<ShopVo> listMyShops();

    /**
     * 创建店铺（ADMIN）。不接受凭证字段。
     */
    Shop createShop(Shop shop);

    /**
     * 更新店铺主数据（ADMIN）。null 字段表示不修改。不接受凭证字段。
     */
    Shop updateShop(Long shopId, Shop patch);

    /**
     * 更新店铺授权状态（ADMIN）。仅接受 1/0/-1。
     */
    Shop updateShopStatus(Long shopId, Integer status);

    /**
     * 把用户分配到店铺并指定店铺级角色（ADMIN）。已存在则更新角色（幂等）。
     */
    void assignUser(Long shopId, Long userId, String role);

    /**
     * 解除用户-店铺绑定（ADMIN）。未绑定时明确失败，不静默返回成功。
     */
    void unassignUser(Long shopId, Long userId);

    /**
     * 某店铺已绑定的用户列表（ADMIN）。供授权界面展示当前绑定——没有它，
     * 分配/解绑就是盲写（看不到现状、解绑只能靠报错试）。
     * <p>
     * 店铺不存在时<b>点名拒绝</b>，不返回空列表冒充「无人绑定」。
     */
    List<ShopUserVo> listShopUsers(Long shopId);

    /**
     * 某用户被授权的店铺 ID 列表（供登录签发 JWT shops claim 复用）。
     */
    List<Long> listShopIdsForUser(Integer userId);
}
