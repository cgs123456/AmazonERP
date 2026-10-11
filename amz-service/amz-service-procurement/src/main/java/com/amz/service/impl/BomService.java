package com.amz.service.impl;

import com.amz.context.UserContext;
import com.amz.exception.CodeErrorException;
import com.amz.mapper.BomMapper;
import com.amz.model.Bom;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物料清单（BOM，组合装）服务。
 * <p>
 * <b>展开语义</b>：{@link #expand} 把「SKU×数量」需求递归展开成子品需求
 * （组合套组合也支持，环检测强制——A 含 B、B 含 A 的脏数据会把内存吃光）。
 * 叶子（无 BOM 的 SKU）原样保留。
 * <p>
 * <b>消费方</b>：order 履约编排（P2.1 配货出库）在库存检查/扣减前先展开，
 * 本地仓按子品备货，组合 SKU 自身没有库存行。
 */
@Slf4j
@Service
public class BomService {

    /** 递归展开深度上限：正常 BOM 树不会超过 5 层，超限按环处理。 */
    private static final int MAX_DEPTH = 8;

    @Autowired
    private BomMapper bomMapper;

    /** 新增/更新一条 BOM 关系（同组合+同子品幂等覆盖）。 */
    public Bom save(Bom bom) {
        if (!com.amz.context.UserContext.isShopAllowed(bom.getShopId())) {
            throw new CodeErrorException("BOM 不属于当前账号可操作的店铺");
        }
        if (bom == null || !StringUtils.hasText(bom.getCompositeSku())
                || !StringUtils.hasText(bom.getChildSku())) {
            throw new CodeErrorException("组合 SKU 与子品 SKU 均不能为空");
        }
        if (bom.getChildQty() == null || bom.getChildQty() <= 0) {
            throw new CodeErrorException("子品数量必须大于 0");
        }
        if (bom.getCompositeSku().trim().equalsIgnoreCase(bom.getChildSku().trim())) {
            throw new CodeErrorException("组合 SKU 与子品 SKU 不能相同（自引用会形成环）");
        }
        Bom existing = bomMapper.selectOne(new LambdaQueryWrapper<Bom>()
                .eq(Bom::getShopId, bom.getShopId())
                .eq(Bom::getCompositeSku, bom.getCompositeSku().trim())
                .eq(Bom::getChildSku, bom.getChildSku().trim()));
        if (existing != null) {
            existing.setChildQty(bom.getChildQty());
            existing.setRemark(bom.getRemark());
            bomMapper.updateById(existing);
            return existing;
        }
        bom.setId(null);
        bom.setCompositeSku(bom.getCompositeSku().trim());
        bom.setChildSku(bom.getChildSku().trim());
        bomMapper.insert(bom);
        return bom;
    }

    /** 删除一条 BOM 关系。 */
    public void delete(Long shopId, Long id) {
        if (!com.amz.context.UserContext.isShopAllowed(shopId)) {
            throw new CodeErrorException("BOM 不属于当前账号可操作的店铺");
        }
        Bom bom = bomMapper.selectById(id);
        if (bom == null || !bom.getShopId().equals(shopId)) {
            throw new CodeErrorException("BOM 不存在或无权访问：id=" + id);
        }
        bomMapper.deleteById(id);
    }

    /** 某组合 SKU 的直接子品。 */
    public List<Bom> listByComposite(Long shopId, String compositeSku) {
        if (!com.amz.context.UserContext.isShopAllowed(shopId)) {
            throw new CodeErrorException("BOM 不属于当前账号可操作的店铺");
        }
        return bomMapper.selectList(new LambdaQueryWrapper<Bom>()
                .eq(Bom::getShopId, shopId)
                .eq(Bom::getCompositeSku, compositeSku == null ? "" : compositeSku.trim()));
    }

    /**
     * 递归展开需求（环检测强制）。
     *
     * @param shopId   店铺
     * @param requests SKU×数量需求
     * @return 展开后的子品需求（叶子聚合去重）
     */
    public List<BomLine> expand(Long shopId, List<BomLine> requests) {
        if (!com.amz.context.UserContext.isShopAllowed(shopId)) {
            throw new CodeErrorException("BOM 不属于当前账号可操作的店铺");
        }
        Map<String, Long> leaves = new LinkedHashMap<>();
        for (BomLine line : requests) {
            if (line == null || !StringUtils.hasText(line.sku())) {
                throw new CodeErrorException("展开明细缺少 SKU");
            }
            if (line.quantity() == null || line.quantity() <= 0) {
                throw new CodeErrorException("展开数量必须大于 0：sku=" + line.sku());
            }
            expandRecursive(shopId, line.sku().trim(), line.quantity().longValue(), leaves, 0);
        }
        List<BomLine> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : leaves.entrySet()) {
            result.add(new BomLine(entry.getKey(), entry.getValue().intValue()));
        }
        return result;
    }

    private void expandRecursive(Long shopId, String sku, long qty,
                                 Map<String, Long> leaves, int depth) {
        if (depth > MAX_DEPTH) {
            throw new CodeErrorException("BOM 展开深度超过 " + MAX_DEPTH
                    + "（疑似环）：sku=" + sku);
        }
        List<Bom> children = bomMapper.selectList(new LambdaQueryWrapper<Bom>()
                .eq(Bom::getShopId, shopId)
                .eq(Bom::getCompositeSku, sku));
        if (children.isEmpty()) {
            // 叶子：聚合数量（同一子品出现在多个组合里时合并）
            leaves.merge(sku, qty, Long::sum);
            return;
        }
        for (Bom child : children) {
            if (child.getChildSku().equalsIgnoreCase(sku)) {
                throw new CodeErrorException("BOM 自引用（环）：sku=" + sku);
            }
            expandRecursive(shopId, child.getChildSku(),
                    qty * (child.getChildQty() == null ? 1 : child.getChildQty()),
                    leaves, depth + 1);
        }
    }

    /** 展开需求的一行：SKU×数量。 */
    public record BomLine(String sku, Integer quantity) {
    }
}
