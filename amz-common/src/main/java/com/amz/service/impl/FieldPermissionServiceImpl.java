package com.amz.service.impl;

import com.amz.constant.RedisConstant;
import com.amz.service.FieldPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字段级数据权限服务实现。
 * <p>
 * 数据源：{@code amz_user.amz_field_permission}（同库 amz_user）——**唯一事实源**。
 * 缓存：Redis Set，key = {@code amz:field:perm:{role}:{entity}}，member = 隐藏字段名。
 * Redis 只是「可重建缓存」，不是事实源：每次加载都以 DB 结果为准做对账（删孤儿 key、
 * 删过期 member、补缺失 member），见 {@link #reconcileRedis}。
 * <p>
 * 容错策略：
 * <ul>
 *   <li>JdbcTemplate 缺失（如 amz-common 单独编译）→ 跳过加载，不抛异常。</li>
 *   <li>DB 加载失败 → 保留上一次成功的内存快照；从未成功过则「全部可见」，记录 WARN。</li>
 *   <li>Redis 查询/对账失败 → 内存兜底缓存仍是本次 DB 结果，业务不中断。</li>
 * </ul>
 */
@Slf4j
@Service
public class FieldPermissionServiceImpl implements FieldPermissionService {

    private static final String LOAD_SQL =
            "SELECT role_code, entity_name, field_name FROM amz_user.amz_field_permission WHERE visible = 0";

    /**
     * 内存兜底缓存：role -> entity -> hidden field set。Redis 不可用时使用。
     * <p>
     * 每次加载整体替换引用而不是就地增删：就地清空会让并发读取短暂看到「无规则 = 全部可见」，
     * 而整体替换是原子的，读到的要么是旧快照、要么是新快照，都是某个时刻的真实规则。
     */
    private volatile Map<String, Map<String, Set<String>>> memoryCache = new ConcurrentHashMap<>();

    /** 标记是否已成功加载过权限规则（避免无规则表时反复尝试 DB 查询）。 */
    private volatile boolean loaded = false;

    /**
     * Redis 可选注入：amz-common 单元测试或独立运行时可能缺失。
     * 使用 ObjectProvider 懒解析：宿主服务 classpath 无 spring-data-redis 时
     * 也不会因类缺失导致 Bean introspect 失败（此前 message 服务启动崩溃根因）。
     */
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<RedisTemplate<String, Object>> redisTemplateProvider;

    /** JDBC 可选注入：同上，ObjectProvider 防止宿主缺 spring-jdbc 时崩溃。 */
    @Autowired(required = false)
    private org.springframework.beans.factory.ObjectProvider<JdbcTemplate> jdbcTemplateProvider;

    private RedisTemplate<String, Object> redis() {
        return redisTemplateProvider != null ? redisTemplateProvider.getIfAvailable() : null;
    }

    private JdbcTemplate jdbc() {
        return jdbcTemplateProvider != null ? jdbcTemplateProvider.getIfAvailable() : null;
    }

    @Override
    public synchronized void loadPermissions() {
        JdbcTemplate template = jdbc();
        if (template == null) {
            log.warn("FieldPermissionService: JdbcTemplate 未注入，跳过权限加载（amz-common 单独运行？）。");
            return;
        }
        try {
            List<Map<String, Object>> rows = template.queryForList(LOAD_SQL);

            Map<String, Map<String, Set<String>>> rebuilt = new ConcurrentHashMap<>();
            Map<String, Set<String>> desiredRedis = new LinkedHashMap<>();
            int ruleCount = 0;
            for (Map<String, Object> row : rows) {
                String role = String.valueOf(row.get("role_code"));
                String entity = String.valueOf(row.get("entity_name"));
                String field = String.valueOf(row.get("field_name"));
                rebuilt
                        .computeIfAbsent(role, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(entity, k -> ConcurrentHashMap.newKeySet())
                        .add(field);
                desiredRedis
                        .computeIfAbsent(RedisConstant.FIELD_PERM_PREFIX + role + ":" + entity,
                                k -> new LinkedHashSet<>())
                        .add(field);
                ruleCount++;
            }
            // 先换内存快照：即使下面的 Redis 对账失败，本次 DB 结果也已经生效。
            memoryCache = rebuilt;
            loaded = true;

            RedisTemplate<String, Object> redis = redis();
            if (redis != null) {
                try {
                    reconcileRedis(redis, desiredRedis);
                } catch (Exception e) {
                    log.warn("FieldPermissionService: Redis 缓存对账失败，本次仅生效内存兜底缓存。原因: {}",
                            e.getMessage());
                }
            }
            log.info("FieldPermissionService: 加载字段权限规则 {} 条，覆盖角色 {} 个，Redis key {} 个",
                    ruleCount, rebuilt.size(), desiredRedis.size());
        } catch (EmptyResultDataAccessException e) {
            memoryCache = new ConcurrentHashMap<>();
            loaded = true;
            log.info("FieldPermissionService: amz_field_permission 表无 visible=0 规则，全部字段可见。");
        } catch (Exception e) {
            // 降级：不阻断启动。保留上一次成功的内存快照（若有），而不是清空成「全部可见」。
            log.warn("FieldPermissionService: 加载字段权限规则失败，沿用上一次成功快照"
                    + "（从未成功加载过则为「全部可见」）。原因: {}", e.getMessage());
        }
    }

    /**
     * 让 Redis 与 DB 结果对账：删掉没有 DB 规则支撑的孤儿 key、删掉已撤销的 member、补上新增的 member。
     * <p>
     * 存在动因（2026-10-10）：原实现只 {@code SADD}、从不删除。隐藏规则一旦在库里被撤销
     * （{@code visible} 改回 1 或整行删除），Redis 里的旧 member 会永久残留；而
     * {@link #getHiddenFields} 优先读 Redis 且非空即返回，于是**撤销权限永远不生效**
     * ——表现为该字段被无限期隐藏。这里做增量对账而不是「先清空再重建」，
     * 是为了避免清空瞬间并发读请求退化成「无规则」。
     */
    private void reconcileRedis(RedisTemplate<String, Object> redis, Map<String, Set<String>> desired) {
        // 用 SCAN 而不是 KEYS：KEYS 在大 keyspace 上是 O(N) 阻塞命令，
        // 会把 Redis 单线程卡住，正是「缓存对账」这种后台动作最不该做的事。
        // 一次删一个 key：Redis Cluster 下多 key 命令要求同 slot，而这里的 key 按 role:entity 分散。
        int orphanCount = 0;
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions()
                .match(RedisConstant.FIELD_PERM_PREFIX + "*")
                .count(256)
                .build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                if (!desired.containsKey(key)) {
                    redis.delete(key);
                    orphanCount++;
                }
            }
        }
        if (orphanCount > 0) {
            log.info("FieldPermissionService: 删除 {} 个已无 DB 规则支撑的 Redis key", orphanCount);
        }
        for (Map.Entry<String, Set<String>> entry : desired.entrySet()) {
            String key = entry.getKey();
            Set<String> expected = entry.getValue();
            Set<Object> current = redis.opsForSet().members(key);
            Set<String> currentNames = new HashSet<>();
            if (current != null) {
                for (Object member : current) {
                    String name = String.valueOf(member);
                    currentNames.add(name);
                    if (!expected.contains(name)) {
                        redis.opsForSet().remove(key, member);
                    }
                }
            }
            for (String field : expected) {
                if (!currentNames.contains(field)) {
                    redis.opsForSet().add(key, field);
                }
            }
        }
    }

    @Override
    public Set<String> getHiddenFields(String role, String entityName) {
        if (role == null || entityName == null) {
            return Collections.emptySet();
        }
        // 优先 Redis
        RedisTemplate<String, Object> redis = redis();
        if (redis != null) {
            try {
                String redisKey = RedisConstant.FIELD_PERM_PREFIX + role + ":" + entityName;
                Set<Object> members = redis.opsForSet().members(redisKey);
                if (members != null && !members.isEmpty()) {
                    Set<String> result = new HashSet<>(members.size());
                    for (Object m : members) {
                        result.add(String.valueOf(m));
                    }
                    return result;
                }
            } catch (Exception e) {
                log.debug("FieldPermissionService: Redis 查询失败，回退内存缓存: {}", e.getMessage());
            }
        }
        // 回退内存兜底
        Map<String, Set<String>> entityMap = memoryCache.get(role);
        if (entityMap == null) {
            return Collections.emptySet();
        }
        Set<String> hidden = entityMap.get(entityName);
        return hidden == null ? Collections.emptySet() : Collections.unmodifiableSet(hidden);
    }

    @Override
    public boolean isFieldVisible(String role, String entityName, String fieldName) {
        if (role == null || entityName == null || fieldName == null) {
            return true;
        }
        return !getHiddenFields(role, entityName).contains(fieldName);
    }
}
