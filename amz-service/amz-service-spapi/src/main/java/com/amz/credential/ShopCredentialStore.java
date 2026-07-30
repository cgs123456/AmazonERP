package com.amz.credential;

import com.amz.mapper.ShopCredentialMapper;
import com.amz.model.ShopCredentialEntity;
import com.amz.util.CryptoUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 店铺凭证存储（内存缓存 + 数据库持久化）。
 * <p>
 * 使用 ConcurrentHashMap 作为一级缓存保证多线程下的读写安全；
 * 同时将凭证（敏感字段已加密）持久化到 amz_shop_credential 表，
 * 服务重启后通过 @PostConstruct 从 DB 重新加载，避免凭证丢失导致 SP-API 功能瘫痪。
 * <p>
 * 敏感字段（clientSecret / refreshToken / accessKey / secretKey）在内存与 DB 中
 * 均以 AES-256-GCM 密文形式保存，{@link #get(Long)} 返回解密后的副本，避免长期持有明文。
 * <p>
 * 所有 DB 操作均带降级处理：异常时仅记录 warn 日志，不阻断 put/get 主流程，
 * 即使 DB 不可用，服务仍可基于内存缓存运行。
 */
@Slf4j
@Component
public class ShopCredentialStore {

    private final ConcurrentHashMap<Long, ShopCredential> store = new ConcurrentHashMap<>();

    @Autowired
    private CryptoUtil cryptoUtil;

    @Autowired(required = false)
    private ShopCredentialMapper shopCredentialMapper;

    /**
     * 启动时从 DB 加载全部凭证到内存缓存。
     * <p>
     * DB 中的敏感字段已为密文，直接放入缓存（不二次加密）。
     * 加载失败时降级为空缓存启动，不阻断应用启动。
     */
    @PostConstruct
    public void loadFromDb() {
        if (shopCredentialMapper == null) {
            log.warn("[ShopCredentialStore] ShopCredentialMapper 未注入，跳过 DB 加载（可能为非 Spring 环境）");
            return;
        }
        try {
            List<ShopCredentialEntity> entities = shopCredentialMapper.selectList(null);
            for (ShopCredentialEntity e : entities) {
                ShopCredential cached = fromEntity(e);
                store.put(cached.getShopId(), cached);
            }
            log.info("[ShopCredentialStore] 从 DB 加载 {} 条店铺凭证到内存缓存", entities.size());
        } catch (Exception e) {
            log.warn("[ShopCredentialStore] 启动时从 DB 加载凭证失败，将以空缓存启动：{}", e.getMessage(), e);
        }
    }

    /**
     * 写入或更新店铺凭证。
     * 敏感字段加密后写入内存缓存与 DB；DB 写入失败仅记录日志，不影响缓存。
     */
    public void put(ShopCredential credential) {
        if (credential == null || credential.getShopId() == null) {
            return;
        }
        ShopCredential encrypted = cloneAndEncrypt(credential);
        store.put(encrypted.getShopId(), encrypted);
        persistToDb(encrypted);
    }

    /**
     * 根据店铺 ID 获取凭证。
     * 优先查内存缓存；未命中时查 DB 并回填缓存。
     * 返回解密后的副本，原缓存条目仍保持密文。
     */
    public ShopCredential get(Long shopId) {
        if (shopId == null) {
            return null;
        }
        ShopCredential stored = store.get(shopId);
        if (stored == null) {
            stored = loadOneFromDb(shopId);
            if (stored != null) {
                store.put(stored.getShopId(), stored);
            }
        }
        return stored == null ? null : cloneAndDecrypt(stored);
    }

    /**
     * 返回当前已配置的全部店铺 ID（即活跃店铺集合）。
     */
    public Set<Long> getActiveShopIds() {
        // ConcurrentHashMap 不允许 null value，无需 filter(e -> e.getValue() != null)
        return store.entrySet().stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * 移除店铺凭证（内存缓存 + DB）。
     */
    public void remove(Long shopId) {
        if (shopId != null) {
            store.remove(shopId);
            deleteFromDb(shopId);
        }
    }

    /**
     * 持久化（upsert）到 DB。异常降级，不阻断主流程。
     */
    private void persistToDb(ShopCredential encrypted) {
        if (shopCredentialMapper == null) {
            return;
        }
        try {
            ShopCredentialEntity entity = toEntity(encrypted);
            LocalDateTime now = LocalDateTime.now();
            ShopCredentialEntity existing = shopCredentialMapper.selectById(encrypted.getShopId());
            if (existing == null) {
                entity.setCreateTime(now);
                entity.setUpdateTime(now);
                shopCredentialMapper.insert(entity);
            } else {
                // 保留原始创建时间，仅更新 update_time 与各凭证字段
                entity.setCreateTime(existing.getCreateTime());
                entity.setUpdateTime(now);
                shopCredentialMapper.updateById(entity);
            }
        } catch (Exception e) {
            log.warn("[ShopCredentialStore] 凭证持久化到 DB 失败，shopId={}：{}", encrypted.getShopId(), e.getMessage(), e);
        }
    }

    /**
     * 从 DB 加载单条凭证（密文形式，未解密）。异常时返回 null。
     */
    private ShopCredential loadOneFromDb(Long shopId) {
        if (shopCredentialMapper == null) {
            return null;
        }
        try {
            ShopCredentialEntity entity = shopCredentialMapper.selectById(shopId);
            return entity == null ? null : fromEntity(entity);
        } catch (Exception e) {
            log.warn("[ShopCredentialStore] 从 DB 查询凭证失败，shopId={}：{}", shopId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从 DB 删除凭证。异常降级，不阻断主流程。
     */
    private void deleteFromDb(Long shopId) {
        if (shopCredentialMapper == null) {
            return;
        }
        try {
            shopCredentialMapper.deleteById(shopId);
        } catch (Exception e) {
            log.warn("[ShopCredentialStore] 从 DB 删除凭证失败，shopId={}：{}", shopId, e.getMessage(), e);
        }
    }

    /**
     * 复制并对敏感字段（clientSecret / refreshToken / accessKey / secretKey）加密。
     */
    private ShopCredential cloneAndEncrypt(ShopCredential src) {
        ShopCredential dst = new ShopCredential();
        dst.setShopId(src.getShopId());
        dst.setClientId(src.getClientId());
        dst.setClientSecret(cryptoUtil.encrypt(src.getClientSecret()));
        dst.setRefreshToken(cryptoUtil.encrypt(src.getRefreshToken()));
        dst.setAccessKey(cryptoUtil.encrypt(src.getAccessKey()));
        dst.setSecretKey(cryptoUtil.encrypt(src.getSecretKey()));
        dst.setRegion(src.getRegion());
        dst.setMarketplaceId(src.getMarketplaceId());
        dst.setSellerId(src.getSellerId());
        return dst;
    }

    /**
     * 复制并对敏感字段（clientSecret / refreshToken / accessKey / secretKey）解密。
     */
    private ShopCredential cloneAndDecrypt(ShopCredential src) {
        ShopCredential dst = new ShopCredential();
        dst.setShopId(src.getShopId());
        dst.setClientId(src.getClientId());
        dst.setClientSecret(cryptoUtil.decrypt(src.getClientSecret()));
        dst.setRefreshToken(cryptoUtil.decrypt(src.getRefreshToken()));
        dst.setAccessKey(cryptoUtil.decrypt(src.getAccessKey()));
        dst.setSecretKey(cryptoUtil.decrypt(src.getSecretKey()));
        dst.setRegion(src.getRegion());
        dst.setMarketplaceId(src.getMarketplaceId());
        dst.setSellerId(src.getSellerId());
        return dst;
    }

    /**
     * 内存密文 ShopCredential -> DB Entity（敏感字段保持密文，直接映射）。
     */
    private ShopCredentialEntity toEntity(ShopCredential encrypted) {
        ShopCredentialEntity e = new ShopCredentialEntity();
        e.setShopId(encrypted.getShopId());
        e.setClientId(encrypted.getClientId());
        e.setClientSecretEncrypted(encrypted.getClientSecret());
        e.setRefreshTokenEncrypted(encrypted.getRefreshToken());
        e.setAccessKeyEncrypted(encrypted.getAccessKey());
        e.setSecretKeyEncrypted(encrypted.getSecretKey());
        e.setRegion(encrypted.getRegion());
        e.setMarketplaceId(encrypted.getMarketplaceId());
        e.setSellerId(encrypted.getSellerId());
        return e;
    }

    /**
     * DB Entity -> 内存密文 ShopCredential（敏感字段保持密文，不二次加密）。
     */
    private ShopCredential fromEntity(ShopCredentialEntity e) {
        ShopCredential c = new ShopCredential();
        c.setShopId(e.getShopId());
        c.setClientId(e.getClientId());
        c.setClientSecret(e.getClientSecretEncrypted());
        c.setRefreshToken(e.getRefreshTokenEncrypted());
        c.setAccessKey(e.getAccessKeyEncrypted());
        c.setSecretKey(e.getSecretKeyEncrypted());
        c.setRegion(e.getRegion());
        c.setMarketplaceId(e.getMarketplaceId());
        c.setSellerId(e.getSellerId());
        return c;
    }
}