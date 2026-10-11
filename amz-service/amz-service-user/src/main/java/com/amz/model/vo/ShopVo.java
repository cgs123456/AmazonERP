package com.amz.model.vo;

import lombok.Data;

/**
 * 店铺列表项（前端店铺选择器的直接数据源）。
 * <p>
 * <b>刻意不含任何凭证字段</b>：这个类会被序列化到浏览器，带 client_secret 或
 * refresh_token（哪怕密文）都是把敏感物推到前端。店铺的授权状态只用
 * {@code credentialConfigured} 布尔位表达——运营需要知道「这家店配没配凭证」，
 * 不需要知道凭证内容。
 */
@Data
public class ShopVo {

    private Long id;

    private String shopName;

    private String marketplaceId;

    /** NA/EU/FE */
    private String region;

    private String sellerId;

    /** 1=已授权 0=未授权 -1=授权过期 */
    private Integer status;

    private String statusText;

    /**
     * 该店铺是否已在 spapi 登记凭证（来自 amz_shop_credential，不是本店的明文列）。
     * 未登记时前端应提示「去连接器中心配置」，而不是显示一个假的「已授权」。
     */
    private boolean credentialConfigured;

    /** 当前登录用户在该店的店铺级角色（ADMIN/OPERATOR/VIEWER）；未分配时为 null。 */
    private String role;
}
