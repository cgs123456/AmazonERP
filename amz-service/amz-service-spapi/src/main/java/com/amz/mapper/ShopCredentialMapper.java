package com.amz.mapper;

import com.amz.model.ShopCredentialEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 店铺凭证 Mapper。
 * <p>
 * 对应 amz_spapi.amz_shop_credential 表，提供凭证的持久化读写。
 * 凭证敏感字段在写入前已由 {@link com.amz.util.CryptoUtil} 加密，
 * 故本 Mapper 仅负责透传密文，不做加解密。
 */
@Mapper
public interface ShopCredentialMapper extends BaseMapper<ShopCredentialEntity> {
}
