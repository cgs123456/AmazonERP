package com.amz.service.impl;

import com.amz.constant.RedisConstant;
import com.amz.service.FieldPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字段级数据权限服务实现。
 * <p>
 * 数据源：{@code amz_user.amz_field_permission}（同库 amz_user）。
 * 缓存：Redis Hash，key = {@code amz:field:perm:{role}:{entity}}，value = 隐藏字段集合。
 * <p>
 * 容错策略：
 * <ul>
 *   <li>JdbcTemplate / RedisTemplate 缺失（如 amz-common 单独编译）→ 服务降级为「全部可见」，不抛异常。</li>
 *   <li>启动加载失败 → 降级为「全部可见」，记录 WARN。</li>
 *   <li>运行时 Redis 查询失败 → 降级为内存兜底缓存（最近一次成功加载的快照）。</li>
 * </ul>
 */
@Slf4j
@Service
public class FieldPermissionServiceImpl implements FieldPermissionService {

    private static final String LOAD_SQL =
            "SELECT role_code, entity_name, field_name FROM amz_user.amz_field_permission WHERE visible = 0";

    /** 内存兜底缓存：role -> entity -> hidden field set。Redis 不可用时使用。 */
    private final Map<String, Map<String, Set<String>>> memoryCache = new ConcurrentHashMap<>();

    /** 标记是否已成功加载过权限规则（避免无规则表时反复尝试 DB 查询）。 */
    private volatile boolean loaded = false;

    /** Redis 可选注入：amz-common 单元测试或独立运行时可能缺失。 */
    @Autowired(required = false)
    private RedisTemplate<String, Object> redisTemplate;

    /** JDBC 可选注入：同上。 */
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Override
    public synchronized void loadPermissions() {
        if (jdbcTemplate == null) {
            log.warn("FieldPermissionService: JdbcTemplate 未注入，跳过权限加载（amz-common 单独运行？）。");
            return;
        }
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(LOAD_SQL);
            memoryCache.clear();
            int count = 0;
            for (Map<String, Object> row : rows) {
                String role = String.valueOf(row.get("role_code"));
                String entity = String.valueOf(row.get("entity_name"));
                String field = String.valueOf(row.get("field_name"));
                memoryCache
                        .computeIfAbsent(role, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(entity, k -> ConcurrentHashMap.newKeySet())
                        .add(field);
                // 同步写入 Redis Hash
                if (redisTemplate != null) {
                    String redisKey = RedisConstant.FIELD_PERM_PREFIX + role + ":" + entity;
                    redisTemplate.opsForSet().add(redisKey, field);
                }
                count++;
            }
            loaded = true;
            log.info("FieldPermissionService: 加载字段权限规则 {} 条，覆盖角色 {} 个", count, memoryCache.size());
        } catch (EmptyResultDataAccessException e) {
            loaded = true;
            log.info("FieldPermissionService: amz_field_permission 表无 visible=0 规则，全部字段可见。");
        } catch (Exception e) {
            // 降级：不阻断启动
            log.warn("FieldPermissionService: 加载字段权限规则失败，降级为「全部可见」。原因: {}", e.getMessage());
        }
    }

    @Override
    public Set<String> getHiddenFields(String role, String entityName) {
        if (role == null || entityName == null) {
            return Collections.emptySet();
        }
        // 优先 Redis
        if (redisTemplate != null) {
            try {
                String redisKey = RedisConstant.FIELD_PERM_PREFIX + role + ":" + entityName;
                Set<Object> members = redisTemplate.opsForSet().members(redisKey);
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
