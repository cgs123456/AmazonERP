package com.amz.model.vo;

import lombok.Data;

/**
 * 店铺-用户绑定项（店铺管理页「已授权用户」列表的数据源）。
 * <p>
 * <b>存在动因</b>：此前只有「分配/解绑」写端点，没有「列出某店铺已绑定哪些用户」的读端点——
 * 授权界面看不到当前绑定，解绑只能靠报错试，属于盲写。本 VO 补上读的一侧。
 * <p>
 * <b>字段克制</b>：只带 userId / nickname / phone / role。不返回完整 User 实体
 * （那会带出 birthday/address 等 PII，与 {@code getUserById} 的掩码口径不一致）。
 * phone 是账号管理识别用户的必要字段（与 {@code /user/list} 对 ADMIN 的口径一致）。
 */
@Data
public class ShopUserVo {

    private Long userId;

    private String nickname;

    private String phone;

    /** 该用户在这家店的店铺级角色（ADMIN/OPERATOR/VIEWER）。 */
    private String role;
}
