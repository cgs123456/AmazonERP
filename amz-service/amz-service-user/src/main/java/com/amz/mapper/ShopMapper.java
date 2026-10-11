package com.amz.mapper;

import com.amz.model.pojo.Shop;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 店铺主数据 Mapper。
 * <p>
 * 此前全仓无 ShopMapper（{@code amz_shop} 只有 ops 服务里一个极简只读实体），
 * 店铺只能靠 SQL 手工插入——这是前端必须写死「Shop A/B/C」的根因。
 * <p>
 * <b>凭证列不参与读写</b>：{@code spapi_refresh_token} / {@code spapi_client_secret}
 * 在实体上挂着 {@code CryptoTypeHandler}（自动加解密），但店铺管理的增删改用不到它们，
 * 且真实凭证的唯一事实源是 spapi 的 {@code amz_shop_credential}——本 Mapper 只做
 * 主数据（名称/站点/区域/卖家 ID/状态），避免在这里再造一条凭证写入路径。
 */
@Mapper
public interface ShopMapper extends BaseMapper<Shop> {
}
