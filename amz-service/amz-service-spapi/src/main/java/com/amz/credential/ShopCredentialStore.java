package com.amz.credential;

import com.amz.mapper.ShopCredentialMapper;
import com.amz.model.ShopCredentialEntity;
import com.amz.util.CryptoUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 店铺凭证存储（有界内存缓存 + 数据库持久化）。
 * <p>
 * 使用有界 TTL 缓存保存数据库中的密文凭证，服务启动时不预加载整张凭证表；
 * {@link #get(Long)} 未命中时才按店铺读取单条记录，避免店铺数增长导致启动时间、
 * 数据库扫描量和 JVM 敏感数据驻留量线性增长。
 * <p>
 * 活跃店铺集合通过只查询 {@code shop_id} 的 keyset 分页接口获取，不读取
 * clientSecret / refreshToken / accessKey / secretKey 等敏感列。
 * <p>
 * 敏感字段（clientSecret / refreshToken / accessKey / secretKey）在内存与 DB 中
 * 均以 AES-256-GCM 密文形式保存，{@link #get(Long)} 返回解密后的副本，避免长期持有明文。
 * <p>
 * 非生产模式保留历史降级行为；生产 profile 通过
 * {@code spapi.credential-store.fail-on-db-error=true} 强制 fail-closed：
 * 启动配置失败拒绝启动、读取失败不得伪装成不存在、写入失败不得先更新内存、
 * 删除失败不得单方面移除缓存。
 */
@Slf4j
@Component
public class ShopCredentialStore {

    /** 单页只读取 shop_id；避免一次查询把全部店铺 ID 拉进一条 SQL 结果集。 */
    private static final int SHOP_ID_QUERY_PAGE_SIZE = 1_000;

    private final ShopCredentialCache store = new ShopCredentialCache();
    private final Object activeShopIdsLock = new Object();

    /** 活跃店铺 ID 快照；避免调度器每次运行都重复查询全表主键。 */
    private volatile Set<Long> activeShopIds;
    private volatile long activeShopIdsExpiresAtMillis;

    @Autowired
    private CryptoUtil cryptoUtil;

    @Autowired(required = false)
    private ShopCredentialMapper shopCredentialMapper;

    /** 生产 profile 置为 true；非生产默认 false，保留离线测试与显式降级能力。 */
    @Value("${spapi.credential-store.fail-on-db-error:false}")
    private boolean failOnDbError;

    /** 进程内密文凭证缓存上限；生产建议 1000，避免店铺数增长导致 JVM 内存无界增长。 */
    @Value("${spapi.credential-store.cache-max-entries:1000}")
    private int cacheMaxEntries = 1_000;

    /** 单条凭证缓存 TTL；到期后下次访问重新读取数据库。 */
    @Value("${spapi.credential-store.cache-ttl-seconds:600}")
    private long cacheTtlSeconds = 600;

    /** 活跃店铺 ID 快照 TTL；写入/删除本机会立即失效。 */
    @Value("${spapi.credential-store.active-shop-ids-ttl-seconds:60}")
    private long activeShopIdsTtlSeconds = 60;

    /**
     * 启动初始化：只校验数据库访问配置和缓存参数，不读取凭证表。
     * <p>
     * 生产模式的凭证存在性与数据库可达性由 {@link ConnectorStartupCheck} 调用
     * {@link #getActiveShopIds()} 进行轻量校验；凭证结构在读取或预检时校验。
     */
    @PostConstruct
    public void loadFromDb() {
        long cacheTtlMillis = TimeUnit.SECONDS.toMillis(cacheTtlSeconds);
        if (cacheMaxEntries <= 0) {
            throw new IllegalStateException("[ShopCredentialStore] spapi.credential-store.cache-max-entries 必须大于 0");
        }
        if (cacheTtlMillis <= 0) {
            throw new IllegalStateException("[ShopCredentialStore] spapi.credential-store.cache-ttl-seconds 必须大于 0");
        }
        if (activeShopIdsTtlSeconds <= 0) {
            throw new IllegalStateException(
                    "[ShopCredentialStore] spapi.credential-store.active-shop-ids-ttl-seconds 必须大于 0");
        }
        store.configure(cacheMaxEntries, cacheTtlMillis);

        if (shopCredentialMapper == null) {
            if (failOnDbError) {
                throw new IllegalStateException("[ShopCredentialStore] 生产模式缺少 ShopCredentialMapper，拒绝启动");
            }
            log.warn("[ShopCredentialStore] ShopCredentialMapper 未注入，使用内存缓存（可能为非 Spring 环境）");
            return;
        }
        log.info("[ShopCredentialStore] 凭证缓存启用按需加载：cacheMaxEntries={}, cacheTtlSeconds={}, "
                        + "activeShopIdsTtlSeconds={}；启动阶段不预加载凭证密文",
                cacheMaxEntries, cacheTtlSeconds, activeShopIdsTtlSeconds);
    }

    /**
     * 写入或更新单条店铺凭证。
     * <p>
     * 委托 {@link #putAll(List)} 执行同一套校验、加密和持久化顺序；事务注解保证
     * 单条写入与批量写入都不会在 DB 失败后先污染内存缓存。
     *
     * @throws IllegalArgumentException 凭证或 shopId 为空，或缺少必需字段
     */
    @Transactional
    public void put(ShopCredential credential) {
        if (credential == null) {
            throw new IllegalArgumentException("invalid shop credential fields: credential");
        }
        putAll(List.of(credential));
    }

    /**
     * 以指定版本执行原子 CAS 写入。
     *
     * <p>{@code expectedVersion == null} 表示创建新记录；记录已存在时冲突。
     * 版本不匹配或并发创建命中主键时抛出 {@link ShopCredentialConcurrentUpdateException}，
     * 调用方必须刷新后重试，不能把旧版本覆盖成新版本。</p>
     */
    @Transactional
    public void putIfVersion(ShopCredential credential, Long expectedVersion) {
        if (credential == null) {
            throw new IllegalArgumentException("invalid shop credential fields: credential");
        }
        if (credential.getShopId() == null) {
            throw new IllegalArgumentException("invalid shop credential fields: shopId");
        }
        List<String> problems = ShopCredentialValidator.validate(credential);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("invalid shop credential fields: " + String.join(",", problems));
        }
        if (expectedVersion != null && expectedVersion < 0L) {
            throw new IllegalArgumentException("invalid shop credential fields: expectedVersion");
        }

        ShopCredential encrypted = cloneAndEncrypt(credential);
        boolean persisted = persistIfVersion(encrypted, expectedVersion);
        encrypted.setVersion(expectedVersion == null ? 0L : expectedVersion + 1L);
        store.put(encrypted.getShopId(), encrypted);
        if (persisted) {
            invalidateActiveShopIds();
        }
    }

    /**
     * 批量写入或更新店铺凭证。
     * <p>
     * 所有输入先完成空值、重复 shopId 和结构完整性校验，再统一加密并逐条持久化。
     * 只有全部 DB 写入完成后才更新内存缓存；生产模式任一写入失败都会抛出并触发事务回滚，
     * 避免首次导入出现“前几条已生效、后几条失败”的半批状态。
     *
     * @throws IllegalArgumentException 输入为空、存在空凭证、重复 shopId 或字段残缺
     */
    @Transactional
    public void putAll(List<ShopCredential> credentials) {
        if (credentials == null || credentials.isEmpty()) {
            throw new IllegalArgumentException("invalid shop credential fields: credentials");
        }

        List<ShopCredential> encryptedCredentials = new ArrayList<>(credentials.size());
        HashSet<Long> shopIds = new HashSet<>();
        for (int index = 0; index < credentials.size(); index++) {
            ShopCredential credential = credentials.get(index);
            if (credential == null) {
                throw new IllegalArgumentException("invalid shop credential fields: credential[" + index + "]");
            }
            if (credential.getShopId() == null) {
                throw new IllegalArgumentException("invalid shop credential fields: shopId");
            }
            if (!shopIds.add(credential.getShopId())) {
                throw new IllegalArgumentException("invalid shop credential fields: duplicate shopId="
                        + credential.getShopId());
            }
            List<String> problems = ShopCredentialValidator.validate(credential);
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("invalid shop credential fields: " + String.join(",", problems));
            }
            encryptedCredentials.add(cloneAndEncrypt(credential));
        }

        Set<Long> persistedShopIds = new HashSet<>();
        for (ShopCredential encrypted : encryptedCredentials) {
            if (persistToDb(encrypted)) {
                persistedShopIds.add(encrypted.getShopId());
            }
        }
        for (ShopCredential encrypted : encryptedCredentials) {
            if (persistedShopIds.contains(encrypted.getShopId())) {
                // 无条件覆盖由 SQL 原子递增 version；驱逐缓存，避免本地保留旧版本。
                store.remove(encrypted.getShopId());
            } else {
                // 非生产降级路径：DB 不可用时保留进程内可用性。
                store.put(encrypted.getShopId(), encrypted);
            }
        }
        invalidateActiveShopIds();
    }

    /**
     * 根据店铺 ID 获取凭证。
     * 优先查内存缓存；未命中时查 DB 并回填缓存。
     * 返回解密后的副本，原缓存条目仍保持密文。
     */
    public ShopCredential get(Long shopId) {
        return getInternal(shopId, true);
    }

    /**
     * 管理侧读取：允许返回残缺凭证，使状态面板和删除操作仍可修复坏记录。
     *
     * <p>SP-API 调用路径必须继续使用 fail-closed 的 {@link #get(Long)}；
     * 本方法不得用于业务调用或凭证明文展示。</p>
     */
    public ShopCredential getForAdmin(Long shopId) {
        return getInternal(shopId, false);
    }

    /**
     * 管理侧「无明文」读取：只返回非敏感字段与敏感字段密文的存在性布尔位。
     *
     * <p>与 {@link #getForAdmin(Long)} 的关键区别：本方法<strong>不做任何解密</strong>，
     * 因此在密文损坏（Base64 非法、GCM tag 校验失败）或密钥轮换不匹配时不会抛出。
     * 状态面板与「删除重建」这类修复路径必须走本方法——否则坏记录会把读取自身
     * 变成 500，管理员再也无法通过 API 修复它，而这恰恰是
     * {@link #getForAdmin(Long)} 声称要解决的问题。
     *
     * @return 记录不存在时返回 null
     */
    public ShopCredentialDescriptor describe(Long shopId) {
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
        if (stored == null) {
            return null;
        }
        return new ShopCredentialDescriptor(
                stored.getShopId(),
                stored.getClientId(),
                hasText(stored.getClientSecret()),
                hasText(stored.getRefreshToken()),
                hasText(stored.getAccessKey()),
                hasText(stored.getSecretKey()),
                stored.getRegion(),
                stored.getMarketplaceId(),
                stored.getSellerId(),
                stored.getUpdateTime());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 仅驱逐本进程缓存，不修改数据库；用于 CAS 冲突后强制刷新版本。
     */
    public void evictCache(Long shopId) {
        store.remove(shopId);
    }

    private ShopCredential getInternal(Long shopId, boolean rejectIncomplete) {
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
        if (stored == null) {
            return null;
        }
        ShopCredential credential = cloneAndDecrypt(stored);
        List<String> problems = ShopCredentialValidator.validate(credential);
        if (rejectIncomplete && !problems.isEmpty()) {
            store.remove(shopId);
            throw new IllegalStateException(String.format(
                    "[ShopCredentialStore] 店铺凭证结构不完整，shopId=%s，字段问题=%s；拒绝返回残缺凭证",
                    shopId, String.join(",", problems)));
        }
        return credential;
    }

    /**
     * 返回当前已配置的全部店铺 ID（即活跃店铺集合）。
     * <p>
     * 有数据库 mapper 时只查询主键，并使用短 TTL 快照避免每次调度重复扫描；
     * 查询失败在生产模式抛出，绝不把数据库故障伪装成“没有店铺”。
     */
    public Set<Long> getActiveShopIds() {
        if (shopCredentialMapper == null) {
            return store.keys();
        }

        long now = System.currentTimeMillis();
        Set<Long> snapshot = activeShopIds;
        if (snapshot != null && now < activeShopIdsExpiresAtMillis) {
            return snapshot;
        }

        synchronized (activeShopIdsLock) {
            now = System.currentTimeMillis();
            snapshot = activeShopIds;
            if (snapshot != null && now < activeShopIdsExpiresAtMillis) {
                return snapshot;
            }
            try {
                Set<Long> loaded = loadActiveShopIdsFromDb();
                activeShopIds = loaded;
                activeShopIdsExpiresAtMillis = now + TimeUnit.SECONDS.toMillis(activeShopIdsTtlSeconds);
                return loaded;
            } catch (Exception e) {
                if (failOnDbError) {
                    throw new IllegalStateException(
                            "[ShopCredentialStore] 查询活跃店铺 ID 失败，拒绝返回不完整集合", e);
                }
                log.warn("[ShopCredentialStore] 查询活跃店铺 ID 失败，使用本地缓存键降级：{}", e.getMessage(), e);
                return store.keys();
            }
        }
    }

    /**
     * 移除店铺凭证（内存缓存 + DB）。
     */
    public void remove(Long shopId) {
        if (shopId != null) {
            deleteFromDb(shopId);
            store.remove(shopId);
            invalidateActiveShopIds();
        }
    }

    /**
     * 只读取主键并按键集分页，避免 {@code selectList(null)} 全表读取凭证密文。
     */
    private Set<Long> loadActiveShopIdsFromDb() {
        Set<Long> ids = new LinkedHashSet<>();
        long cursor = Long.MIN_VALUE;
        while (true) {
            List<Long> page = shopCredentialMapper.selectShopIdsAfter(cursor, SHOP_ID_QUERY_PAGE_SIZE);
            if (page == null || page.isEmpty()) {
                break;
            }
            Long last = null;
            for (Long shopId : page) {
                if (shopId == null) {
                    throw new IllegalStateException("活跃店铺 ID 查询返回了 null shop_id");
                }
                ids.add(shopId);
                last = shopId;
            }
            if (page.size() < SHOP_ID_QUERY_PAGE_SIZE) {
                break;
            }
            if (last == null || last <= cursor) {
                throw new IllegalStateException("活跃店铺 ID 分页游标未向前推进，拒绝返回不完整集合");
            }
            cursor = last;
        }
        return Collections.unmodifiableSet(ids);
    }

    private void invalidateActiveShopIds() {
        activeShopIds = null;
        activeShopIdsExpiresAtMillis = 0L;
    }

    /**
     * 按预期版本原子持久化；版本不匹配或并发创建时抛出冲突，绝不降级覆盖。
     */
    private boolean persistIfVersion(ShopCredential encrypted, Long expectedVersion) {
        if (shopCredentialMapper == null) {
            return handleDbFailure("ShopCredentialMapper 未注入，无法执行 CAS 持久化",
                    encrypted.getShopId(), null);
        }
        try {
            ShopCredentialEntity entity = toEntity(encrypted);
            LocalDateTime now = encrypted.getUpdateTime() == null ? LocalDateTime.now() : encrypted.getUpdateTime();
            entity.setUpdateTime(now);
            if (expectedVersion == null) {
                entity.setCreateTime(now);
                try {
                    int inserted = shopCredentialMapper.insert(entity);
                    if (inserted != 1) {
                        throw new IllegalStateException("凭证创建未写入任何行，shopId=" + encrypted.getShopId());
                    }
                } catch (DuplicateKeyException e) {
                    throw new ShopCredentialConcurrentUpdateException(encrypted.getShopId());
                }
            } else {
                int updated = shopCredentialMapper.updateIfVersion(entity, expectedVersion);
                if (updated != 1) {
                    throw new ShopCredentialConcurrentUpdateException(encrypted.getShopId());
                }
            }
            return true;
        } catch (ShopCredentialConcurrentUpdateException e) {
            throw e;
        } catch (Exception e) {
            return handleDbFailure("凭证 CAS 持久化到 DB 失败", encrypted.getShopId(), e);
        }
    }

    /**
     * 持久化（upsert）到 DB。生产模式异常向上抛出；非生产模式记录 warn 并返回 false。
     */
    private boolean persistToDb(ShopCredential encrypted) {
        if (shopCredentialMapper == null) {
            return handleDbFailure("ShopCredentialMapper 未注入，无法持久化", encrypted.getShopId(), null);
        }
        try {
            ShopCredentialEntity entity = toEntity(encrypted);
            LocalDateTime now = encrypted.getUpdateTime() == null ? LocalDateTime.now() : encrypted.getUpdateTime();
            ShopCredentialEntity existing = shopCredentialMapper.selectById(encrypted.getShopId());
            if (existing == null) {
                entity.setCreateTime(now);
                entity.setUpdateTime(now);
                int inserted = shopCredentialMapper.insert(entity);
                if (inserted != 1) {
                    return handleDbFailure("凭证创建未写入任何行", encrypted.getShopId(), null);
                }
            } else {
                // 保留原始创建时间；由 SQL 原子递增 version，避免与 CAS 写路径产生版本分叉。
                entity.setCreateTime(existing.getCreateTime());
                entity.setUpdateTime(now);
                int updated = shopCredentialMapper.updateUnconditionally(entity);
                if (updated != 1) {
                    return handleDbFailure("凭证更新未命中记录或未写入任何行", encrypted.getShopId(), null);
                }
            }
            return true;
        } catch (Exception e) {
            return handleDbFailure("凭证持久化到 DB 失败", encrypted.getShopId(), e);
        }
    }

    /**
     * 从 DB 加载单条凭证（密文形式，未解密）。生产模式异常向上抛出；非生产模式返回 null。
     */
    private ShopCredential loadOneFromDb(Long shopId) {
        if (shopCredentialMapper == null) {
            if (failOnDbError) {
                throw new IllegalStateException("[ShopCredentialStore] 生产模式缺少 ShopCredentialMapper，无法查询凭证 shopId="
                        + shopId);
            }
            return null;
        }
        try {
            ShopCredentialEntity entity = shopCredentialMapper.selectById(shopId);
            return entity == null ? null : fromEntity(entity);
        } catch (Exception e) {
            if (failOnDbError) {
                throw new IllegalStateException("[ShopCredentialStore] 从 DB 查询凭证失败，shopId="
                        + shopId, e);
            }
            log.warn("[ShopCredentialStore] 从 DB 查询凭证失败，shopId={}：{}", shopId, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 从 DB 删除凭证。生产模式异常向上抛出，调用方不会继续移除内存缓存。
     */
    private void deleteFromDb(Long shopId) {
        if (shopCredentialMapper == null) {
            if (failOnDbError) {
                throw new IllegalStateException("[ShopCredentialStore] 生产模式缺少 ShopCredentialMapper，无法删除凭证 shopId="
                        + shopId);
            }
            return;
        }
        try {
            shopCredentialMapper.deleteById(shopId);
        } catch (Exception e) {
            if (failOnDbError) {
                throw new IllegalStateException("[ShopCredentialStore] 从 DB 删除凭证失败，shopId="
                        + shopId, e);
            }
            log.warn("[ShopCredentialStore] 从 DB 删除凭证失败，shopId={}：{}", shopId, e.getMessage(), e);
        }
    }

    /**
     * 统一处理 DB 故障：生产模式抛出，非生产模式记录 warn 并返回 false。
     */
    private boolean handleDbFailure(String operation, Long shopId, Exception cause) {
        if (failOnDbError) {
            throw new IllegalStateException("[ShopCredentialStore] " + operation + "，shopId=" + shopId, cause);
        }
        if (cause == null) {
            log.warn("[ShopCredentialStore] {}，shopId={}", operation, shopId);
        } else {
            log.warn("[ShopCredentialStore] {}，shopId={}：{}", operation, shopId, cause.getMessage(), cause);
        }
        return false;
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
        dst.setVersion(src.getVersion());
        dst.setUpdateTime(src.getUpdateTime());
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
        dst.setVersion(src.getVersion());
        dst.setUpdateTime(src.getUpdateTime());
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
        e.setVersion(encrypted.getVersion());
        e.setUpdateTime(encrypted.getUpdateTime());
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
        c.setVersion(e.getVersion());
        c.setUpdateTime(e.getUpdateTime());
        return c;
    }
}
