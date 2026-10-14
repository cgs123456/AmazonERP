package com.amz.service.impl;

import com.amz.constant.ExceptionConstant;
import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.ShopMapper;
import com.amz.mapper.UserMapper;
import com.amz.mapper.UserShopMapper;
import com.amz.model.pojo.Shop;
import com.amz.model.pojo.User;
import com.amz.model.pojo.UserShop;
import com.amz.model.vo.ShopUserVo;
import com.amz.model.vo.ShopVo;
import com.amz.service.ShopService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 店铺主数据服务。
 * <p>
 * <b>存在动因</b>：全仓此前没有任何店铺读/写端点，前端只能在 {@code main.ts} 里写死
 * 「Shop A (US) / Shop B (UK) / Shop C (DE)」三个假店铺——一个多店铺 ERP 的店铺选择器
 * 数据是编造的，店铺切换、按店过滤、按店鉴权全都建立在假数据上。本服务补上真实来源。
 * <p>
 * <b>权限模型</b>：店铺 CRUD 与用户-店铺分配限定 ADMIN；「我的店铺」列表按
 * {@code amz_user_shop} 归属返回，ADMIN 可见全部（ADMIN 看不到就无法给别人分店）。
 * 所有店铺级校验都在这里做，不依赖调用方传身份。
 * <p>
 * <b>JWT 时效限制（已知，文档已记）</b>：登录签发的 token 带 shops claim，有效期 24 小时。
 * 分配/回收店铺后，该用户必须重新登录（或刷新 token）才能拿到新的 shop 集合——
 * 这是刻意不引入「每次请求查库」换来的代价：鉴权路径保持无 DB 依赖，避免每个请求
 * 都打一次库。前端在分配完成后会提示受影响用户重新登录。
 */
@Slf4j
@Service
public class ShopServiceImpl implements ShopService {

    /** 店铺级角色白名单：与 amz_user_shop.role 的 DDL 注释一致。 */
    private static final Set<String> SHOP_ROLES = Set.of("ADMIN", "OPERATOR", "VIEWER");

    /** 站点所属区域枚举：与 amz_shop.region 的 DDL 注释（NA/EU/FE）一致。 */
    private static final Set<String> REGIONS = Set.of("NA", "EU", "FE");

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private UserShopMapper userShopMapper;

    @Autowired
    private UserMapper userMapper;

    /** 只读跨库查询：凭证事实源在 spapi 库，本服务不写它。 */
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Override
    public List<ShopVo> listMyShops() {
        Integer userId = UserContext.getUserId();
        if (userId == null) {
            throw new CodeErrorException("未获取到登录用户");
        }
        boolean admin = isAdmin();

        List<UserShop> bindings = new ArrayList<>();
        if (!admin) {
            LambdaQueryWrapper<UserShop> q = new LambdaQueryWrapper<>();
            q.eq(UserShop::getUserId, userId.longValue());
            bindings = userShopMapper.selectList(q);
        }
        if (!admin && bindings.isEmpty()) {
            // 明确返回空而不是「默认给几家常驻店铺」：没有授权就是没有，
            // 前端拿到空列表会提示联系管理员，而不是让用户以为看到了全部数据。
            return Collections.emptyList();
        }

        Set<Long> boundShopIds = new HashSet<>();
        for (UserShop binding : bindings) {
            boundShopIds.add(binding.getShopId());
        }
        LambdaQueryWrapper<Shop> shopQuery = new LambdaQueryWrapper<>();
        if (!admin) {
            shopQuery.in(Shop::getId, boundShopIds);
        }
        shopQuery.orderByAsc(Shop::getId);
        List<Shop> shops = shopMapper.selectList(shopQuery);

        // 店铺级角色：非 ADMIN 时取绑定表里的 role（同一用户在不同店可不同角色）
        java.util.Map<Long, String> roleByShop = new java.util.HashMap<>();
        for (UserShop binding : bindings) {
            roleByShop.put(binding.getShopId(), binding.getRole());
        }

        List<ShopVo> result = new ArrayList<>();
        for (Shop shop : shops) {
            ShopVo vo = new ShopVo();
            vo.setId(shop.getId());
            vo.setShopName(shop.getShopName());
            vo.setMarketplaceId(shop.getMarketplaceId());
            vo.setRegion(shop.getRegion());
            vo.setSellerId(shop.getSellerId());
            vo.setStatus(shop.getStatus());
            vo.setStatusText(statusText(shop.getStatus()));
            vo.setCredentialConfigured(hasCredential(shop.getId()));
            vo.setRole(admin ? "ADMIN" : roleByShop.get(shop.getId()));
            result.add(vo);
        }
        return result;
    }

    @Override
    public Shop createShop(Shop shop) {
        requireAdmin();
        validateShop(shop, false);
        shop.setId(null);
        shopMapper.insert(shop);
        log.info("创建店铺 id={} name={} marketplace={}", shop.getId(), shop.getShopName(),
                shop.getMarketplaceId());
        return shop;
    }

    @Override
    public Shop updateShop(Long shopId, Shop patch) {
        requireAdmin();
        if (shopId == null) {
            throw new CodeErrorException("店铺 ID 不能为空");
        }
        Shop existing = shopMapper.selectById(shopId);
        if (existing == null) {
            throw new CodeErrorException("店铺不存在：id=" + shopId);
        }
        validateShop(patch, true);
        if (patch.getShopName() != null) existing.setShopName(patch.getShopName());
        if (patch.getMarketplaceId() != null) existing.setMarketplaceId(patch.getMarketplaceId());
        if (patch.getRegion() != null) existing.setRegion(patch.getRegion());
        if (patch.getSellerId() != null) existing.setSellerId(patch.getSellerId());
        if (patch.getStatus() != null) existing.setStatus(patch.getStatus());
        shopMapper.updateById(existing);
        log.info("更新店铺 id={}", shopId);
        return existing;
    }

    @Override
    public Shop updateShopStatus(Long shopId, Integer status) {
        requireAdmin();
        if (shopId == null) {
            throw new CodeErrorException("店铺 ID 不能为空");
        }
        if (status == null || (status != 1 && status != 0 && status != -1)) {
            throw new CodeErrorException("状态只支持 1=已授权 / 0=未授权 / -1=授权过期");
        }
        Shop existing = shopMapper.selectById(shopId);
        if (existing == null) {
            throw new CodeErrorException("店铺不存在：id=" + shopId);
        }
        existing.setStatus(status);
        shopMapper.updateById(existing);
        log.info("更新店铺状态 id={} status={}", shopId, status);
        return existing;
    }

    @Override
    public void assignUser(Long shopId, Long userId, String role) {
        requireAdmin();
        if (shopId == null || userId == null) {
            throw new CodeErrorException("店铺 ID 与用户 ID 均不能为空");
        }
        String normalized = role == null ? "VIEWER" : role.trim().toUpperCase(Locale.ROOT);
        if (!SHOP_ROLES.contains(normalized)) {
            throw new CodeErrorException("店铺角色只支持 ADMIN/OPERATOR/VIEWER，实际收到「" + role + "」");
        }
        if (shopMapper.selectById(shopId) == null) {
            throw new CodeErrorException("店铺不存在：id=" + shopId);
        }
        if (userMapper.selectById(userId.intValue()) == null) {
            throw new CodeErrorException("用户不存在：id=" + userId);
        }

        LambdaQueryWrapper<UserShop> q = new LambdaQueryWrapper<>();
        q.eq(UserShop::getShopId, shopId).eq(UserShop::getUserId, userId);
        UserShop existing = userShopMapper.selectOne(q);
        if (existing != null) {
            existing.setRole(normalized);
            userShopMapper.updateById(existing);
            log.info("更新用户-店铺绑定 shopId={} userId={} role={}", shopId, userId, normalized);
            return;
        }
        UserShop binding = new UserShop();
        binding.setShopId(shopId);
        binding.setUserId(userId);
        binding.setRole(normalized);
        userShopMapper.insert(binding);
        log.info("新增用户-店铺绑定 shopId={} userId={} role={}", shopId, userId, normalized);
    }

    @Override
    public void unassignUser(Long shopId, Long userId) {
        requireAdmin();
        if (shopId == null || userId == null) {
            throw new CodeErrorException("店铺 ID 与用户 ID 均不能为空");
        }
        LambdaQueryWrapper<UserShop> q = new LambdaQueryWrapper<>();
        q.eq(UserShop::getShopId, shopId).eq(UserShop::getUserId, userId);
        int removed = userShopMapper.delete(q);
        if (removed == 0) {
            throw new CodeErrorException("该用户未绑定此店铺，无需解绑");
        }
        log.info("解除用户-店铺绑定 shopId={} userId={}", shopId, userId);
    }

    @Override
    public List<ShopUserVo> listShopUsers(Long shopId) {
        requireAdmin();
        if (shopId == null) {
            throw new CodeErrorException("店铺 ID 不能为空");
        }
        if (shopMapper.selectById(shopId) == null) {
            // 不返回空列表冒充「无人绑定」：店铺不存在与「确实没授权任何人」是两件事
            throw new CodeErrorException("店铺不存在：id=" + shopId);
        }
        LambdaQueryWrapper<UserShop> q = new LambdaQueryWrapper<>();
        q.eq(UserShop::getShopId, shopId).orderByAsc(UserShop::getUserId);
        List<UserShop> bindings = userShopMapper.selectList(q);

        List<ShopUserVo> result = new ArrayList<>();
        for (UserShop binding : bindings) {
            ShopUserVo vo = new ShopUserVo();
            vo.setUserId(binding.getUserId());
            vo.setRole(binding.getRole());
            User user = binding.getUserId() == null ? null : userMapper.selectById(binding.getUserId().intValue());
            if (user != null) {
                vo.setNickname(user.getNickname());
                vo.setPhone(user.getPhone());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public List<Long> listShopIdsForUser(Integer userId) {
        LambdaQueryWrapper<UserShop> q = new LambdaQueryWrapper<>();
        q.eq(UserShop::getUserId, userId.longValue());
        List<UserShop> bindings = userShopMapper.selectList(q);
        List<Long> ids = new ArrayList<>();
        for (UserShop binding : bindings) {
            if (binding.getShopId() != null) {
                ids.add(binding.getShopId());
            }
        }
        return ids;
    }

    // ============ 内部 ============

    private void requireAdmin() {
        String role = UserContext.getRole();
        if (!"ADMIN".equalsIgnoreCase(role)) {
            throw new CodeErrorException("无权限执行该操作（需要角色：ADMIN）");
        }
    }

    private boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(UserContext.getRole());
    }

    private void validateShop(Shop shop, boolean partial) {
        if (shop == null) {
            throw new CodeErrorException("店铺内容不能为空");
        }
        if (!partial || shop.getShopName() != null) {
            if (!StringUtils.hasText(shop.getShopName())) {
                throw new CodeErrorException("店铺名称不能为空");
            }
            if (shop.getShopName().length() > 100) {
                throw new CodeErrorException("店铺名称最长 100 字符");
            }
        }
        if (!partial || shop.getMarketplaceId() != null) {
            if (!StringUtils.hasText(shop.getMarketplaceId())) {
                throw new CodeErrorException("marketplaceId 不能为空");
            }
        }
        if (shop.getRegion() != null && !REGIONS.contains(shop.getRegion().trim().toUpperCase(Locale.ROOT))) {
            throw new CodeErrorException("region 只支持 NA/EU/FE，实际收到「" + shop.getRegion() + "」");
        }
        if (shop.getRegion() != null) {
            shop.setRegion(shop.getRegion().trim().toUpperCase(Locale.ROOT));
        }
        // 凭证列一律不接受来自店铺管理接口的写入：
        // 真实凭证唯一入口是 spapi 的 /spapi/credentials（加密落库），
        // 这里若也接受，就会出现同一份凭证两个写入源、两个加密口径。
        if (shop.getSpapiRefreshToken() != null || shop.getSpapiClientSecret() != null
                || shop.getSpapiClientId() != null) {
            throw new CodeErrorException("店铺凭证不由本接口维护，请到连接器中心配置（/spapi/credentials）");
        }
    }

    /**
     * 凭证是否已在 spapi 登记（只读跨库查询）。
     * <p>
     * 失败时返回 false 并记日志，而不是抛出——「不知道配没配」不能阻断店铺列表，
     * 但也不能谎报「已配置」。前端据此提示去连接器中心登记。
     */
    private boolean hasCredential(Long shopId) {
        if (jdbcTemplate == null || shopId == null) {
            return false;
        }
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM amz_spapi.amz_shop_credential WHERE shop_id = ?",
                    Integer.class, shopId);
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("查询店铺凭证状态失败 shopId={}，按未配置处理。原因: {}", shopId, e.getMessage());
            return false;
        }
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "已授权";
            case 0 -> "未授权";
            case -1 -> "授权过期";
            default -> "未知(" + status + ")";
        };
    }
}
