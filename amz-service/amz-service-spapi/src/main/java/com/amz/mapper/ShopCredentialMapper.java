package com.amz.mapper;

import com.amz.model.ShopCredentialEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 店铺凭证 Mapper。
 * <p>
 * 对应 amz_spapi.amz_shop_credential 表，提供凭证的持久化读写。
 * 凭证敏感字段在写入前已由 {@link com.amz.util.CryptoUtil} 加密，
 * 故本 Mapper 仅负责透传密文，不做加解密。
 */
@Mapper
public interface ShopCredentialMapper extends BaseMapper<ShopCredentialEntity> {

    /**
     * 按主键升序分页读取店铺 ID，不读取任何凭证密文。
     * <p>
     * 使用 keyset 分页而不是 OFFSET：店铺数增长时，后续页不会因为前置行扫描而变慢；
     * 调用方用上一页最后一个 shopId 作为下一轮 cursor。首次查询传 {@link Long#MIN_VALUE}。
     *
     * @param cursor 上一页最后一个 shop_id；首次查询传 {@link Long#MIN_VALUE}
     * @param limit  单页大小，必须由调用方限制上限
     * @return 按 shop_id 升序排列的店铺 ID 页
     */
    @Select("""
            SELECT shop_id
              FROM amz_shop_credential
             WHERE shop_id > #{cursor}
             ORDER BY shop_id ASC
             LIMIT #{limit}
            """)
    List<Long> selectShopIdsAfter(@Param("cursor") long cursor, @Param("limit") int limit);

    /**
     * 按版本号执行原子 CAS 更新。
     *
     * @return 1 表示更新成功；0 表示版本不匹配或记录已被并发删除
     */
    @Update("""
            UPDATE amz_shop_credential
               SET client_id = #{credential.clientId},
                   client_secret_encrypted = #{credential.clientSecretEncrypted},
                   refresh_token_encrypted = #{credential.refreshTokenEncrypted},
                   access_key_encrypted = #{credential.accessKeyEncrypted},
                   secret_key_encrypted = #{credential.secretKeyEncrypted},
                   region = #{credential.region},
                   marketplace_id = #{credential.marketplaceId},
                   seller_id = #{credential.sellerId},
                   update_time = #{credential.updateTime},
                   version = version + 1
             WHERE shop_id = #{credential.shopId}
               AND version = #{expectedVersion}
            """)
    int updateIfVersion(@Param("credential") ShopCredentialEntity credential,
                        @Param("expectedVersion") long expectedVersion);

    /**
     * 全量覆盖凭证，忽略调用方版本；SQL 侧原子递增版本，供 bootstrap 导入使用。
     */
    @Update("""
            UPDATE amz_shop_credential
               SET client_id = #{credential.clientId},
                   client_secret_encrypted = #{credential.clientSecretEncrypted},
                   refresh_token_encrypted = #{credential.refreshTokenEncrypted},
                   access_key_encrypted = #{credential.accessKeyEncrypted},
                   secret_key_encrypted = #{credential.secretKeyEncrypted},
                   region = #{credential.region},
                   marketplace_id = #{credential.marketplaceId},
                   seller_id = #{credential.sellerId},
                   update_time = #{credential.updateTime},
                   version = version + 1
             WHERE shop_id = #{credential.shopId}
            """)
    int updateUnconditionally(@Param("credential") ShopCredentialEntity credential);
}
