package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.context.UserContext;
import com.amz.mapper.ProductMapper;
import com.amz.model.dto.ProductDto;
import com.amz.model.pojo.Product;
import com.amz.model.vo.ProductVo;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;
import com.amz.result.Result;
import com.amz.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 旧商品接口（{@code Product} 实体通路）的实现。
 * <p>
 * 五个 HTTP 入口在入口即拒绝：它们映射的列在现表里不存在，落 SQL 只会得到 1054/500。
 * 可用的商品读写在 {@code ProductMasterServiceImpl}（{@code AmzProduct} ↔ 新 schema）。
 * searchProducts 不在 HTTP 上映射，仍保留其分页契约测试；一旦被暴露，需要同步加同样的守卫。
 */
@Service
@Slf4j
public class ProductServiceImpl implements ProductService {

    /**
     * Product 实体（@Deprecated）映射的旧列 name/type/image/sales/stock/user_id 在
     * amz_product 现表里都不存在：Flyway V1 建的是新 schema（shop_id/sku/asin/marketplace_id/title），
     * V2 只补了 product_type，没有任何迁移补回旧列；legacy 建表脚本里这张表也已是新 schema。
     * 因此这五个接口一调到 SQL 层就是 MySQL 1054 → HTTP 500，可读信息只出现在堆栈里
     * （订单服务经 {@code ProductClient.getProductById} 走的就是这条死路）。
     * 守卫放在入口，把拒绝理由和可用替代一起还给调用方；要恢复旧接口，先提交补齐列的迁移 SQL。
     */
    static final String LEGACY_DRIFT_REFUSAL =
            "商品旧接口不可用：实体 Product 仍映射 amz_product 的旧列（name/type/image/sales/stock/user_id），"
            + "而该表已是新 schema（shop_id/sku/asin/marketplace_id/title），SQL 会以 Unknown column 失败。"
            + "请改用 /product/master/list/{shopId}、/product/master/{shopId}/{id} 等主数据接口；"
            + "确需旧接口请先提交补齐列的迁移 SQL。";

    private <T> Result<T> refuseLegacyDrift(String api) {
        log.warn("旧商品接口被实体-表漂移守卫拒绝：{}", api);
        return Result.failure(LEGACY_DRIFT_REFUSAL);
    }

    @Autowired
    private ProductMapper productMapper;

    @Override
    public Result<List<Product>> getProductList() {
        return refuseLegacyDrift("GET /product/getProductList");
    }

    @Override
    public Result<ProductVo> getProduct(Integer productId) {
        // 原实现要查商品行 + 店铺行 + Mongo 属性，三段引用的列在现表里都不存在
        return refuseLegacyDrift("GET /product/getProduct/" + productId);
    }

    @Override
    public Result<List<Product>> getProductByShop(Integer productId) {
        return refuseLegacyDrift("GET /product/getProductsByShop/" + productId);
    }

    @Override
    public Result<Void> postProduct(ProductDto productDto) {
        // 写路径同样拒绝：insert 会显式列出 name/type/... 这些不存在的列
        return refuseLegacyDrift("POST /product/postProduct");
    }

    @Override
    public Result<Void> updateProduct(ProductDto productDto) {
        return refuseLegacyDrift("PUT /product/updateProduct");
    }

    @Override
    public Result<List<Product>> searchProducts(String keyword, PageRequest page) {
        // 搜索限定当前店铺（与 getProductList 同口径，避免跨店数据暴露）
        Long shopId = UserContext.getShopId();
        if (shopId == null) {
            return Result.failure("请先选择店铺");
        }
        PageRequest req = page == null ? PageRequest.first(PageRequest.DEFAULT_SIZE) : page;
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getShopId, shopId.intValue());
        if (keyword != null && !keyword.trim().isEmpty()) {
            wrapper.and(w -> w.like(Product::getName, keyword)
                   .or().like(Product::getDescription, keyword)
                   .or().like(Product::getBrand, keyword));
        }
        // keyset：商品 id 自增，按 id DESC 翻页与新增商品互不干扰（OFFSET 会漏行/重复）
        if (req.hasCursor()) {
            wrapper.lt(Product::getId, req.cursorId().intValue());
        }
        wrapper.orderByDesc(Product::getId)
               .last("LIMIT " + req.probeSize());
        List<Product> rows = productMapper.selectList(wrapper);
        PageResult<Product> result = PageResult.of(rows, req.size(),
                p -> PageRequest.encodeCursor(String.valueOf(p.getId())));
        if (result.truncated()) {
            log.warn("商品搜索被分页截断：shopId={} keyword={} size={}，请用 nextCursor 继续翻页",
                    shopId, keyword, req.size());
        }
        return Result.paged(result);
    }

    @Override
    public Product selectById(Integer id) {
        return productMapper.selectById(id);
    }

    @Override
    public void updateById(Product product) {
        productMapper.updateById(product);
    }
}
