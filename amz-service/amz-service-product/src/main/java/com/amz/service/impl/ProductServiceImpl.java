package com.amz.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.amz.context.UserContext;
import com.amz.mapper.ProductMapper;
import com.amz.mapper.ShopMapper;
import com.amz.model.dto.ProductDto;
import com.amz.model.pojo.Product;
import com.amz.model.pojo.ProductAttribute;
import com.amz.model.pojo.Shop;
import com.amz.model.vo.ProductVo;
import com.amz.result.Result;
import com.amz.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Slf4j
public class ProductServiceImpl implements ProductService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ShopMapper shopMapper;

    @Autowired
    private MongoTemplate mongoTemplate;

    @Override
    public Result<List<Product>> getProductList() {
        // 多店铺隔离：仅返回当前用户所选店铺的商品（shopId 经网关/拦截器校验后写入 UserContext）。
        // 原实现 selectList(null) 会把所有店铺的商品全部返回，构成跨店铺数据暴露。
        Long shopId = UserContext.getShopId();
        if (shopId == null) {
            return Result.failure("请先选择店铺");
        }
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getShopId, shopId.intValue());
        List<Product> products = productMapper.selectList(wrapper);
        return Result.success(products);
    }

    @Override
    public Result<ProductVo> getProduct(Integer productId) {
        // 1.获取商品信息
        Product product = productMapper.selectById(productId);
        if (product == null) {
            return Result.failure("商品不存在");
        }
        // 2.店铺归属校验（@ShopScoped 切面覆盖不到路径参数，需显式校验防跨店枚举）
        if (product.getShopId() == null
                || !UserContext.isShopAllowed(product.getShopId().longValue())) {
            return Result.failure("商品不存在或无权访问");
        }
        // 2.获取店铺信息
        Shop shop = shopMapper.selectById(product.getShopId());
        // 获取商品属性
        ProductAttribute productAttribute
                = mongoTemplate.findOne(new Query(Criteria.where("productId").is(product.getId())), ProductAttribute.class);
        // 3.设置vo
        ProductVo productVo = new ProductVo();
        productVo.setProduct(product);
        productVo.setShop(shop);
        if (productAttribute != null) productVo.setCustomAttributes(productAttribute.getCustomAttributes());
        return Result.success(productVo);
    }

    @Override
    public Result<List<Product>> getProductByShop(Integer productId) {
        // 1.根据产品id获取店铺id
        Product product = productMapper.selectById(productId);
        if (product == null) {
            return Result.failure("商品不存在");
        }
        // 目标商品本身先做归属校验，避免以他人商品 id 为跳板拉取其店铺商品
        if (product.getShopId() == null
                || !UserContext.isShopAllowed(product.getShopId().longValue())) {
            return Result.failure("商品不存在或无权访问");
        }
        Integer shopId = product.getShopId();
        // 2.查询同店铺其余产品（排除条件谓词下推到 DB，避免全店商品载入内存后再过滤）
        LambdaQueryWrapper<Product> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Product::getShopId, shopId)
                .ne(Product::getId, productId);
        List<Product> products = productMapper.selectList(queryWrapper);
        return Result.success(products);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> postProduct(ProductDto productDto) {
        if (productDto == null) {
            return Result.failure("商品数据不能为空");
        }
        // 店铺归属校验（请求体 shopId 切面覆盖不到，显式校验）
        if (productDto.getShopId() == null
                || !UserContext.isShopAllowed(productDto.getShopId().longValue())) {
            return Result.failure("无权操作该店铺商品");
        }
        if (productDto.getName() == null || productDto.getName().isBlank()) {
            return Result.failure("商品名称不能为空");
        }
        // 1.上传商品（从 DTO 拷贝字段后再落库，避免空行垃圾数据）
        Product product = new Product();
        BeanUtils.copyProperties(productDto, product);
        product.setId(null);
        product.setSales(0);
        // 先插入商品获取 ID
        productMapper.insert(product);

        // 2.上传商品属性（若未提供则跳过，商品记录已落库）
        ProductAttribute productAttribute = productDto.getProductAttribute();
        if (productAttribute != null) {
            productAttribute.setProductId(product.getId());
            mongoTemplate.insert(productAttribute);
        } else {
            log.warn("商品属性为空，仅保存商品主记录：productId={}", product.getId());
        }

        return Result.success(null);
    }

    @Override
    public Result<Void> updateProduct(ProductDto productDto) {
        if (productDto == null || productDto.getId() == null) {
            return Result.failure("商品数据不能为空");
        }
        // 先查库做归属校验，避免伪造 id 跨店覆盖他人商品
        Product existed = productMapper.selectById(productDto.getId());
        if (existed == null) {
            return Result.failure("商品不存在");
        }
        if (existed.getShopId() == null
                || !UserContext.isShopAllowed(existed.getShopId().longValue())) {
            return Result.failure("无权操作该店铺商品");
        }
        Product product = new Product();
        BeanUtils.copyProperties(productDto, product);
        // 锁定归属店铺：禁止借更新把商品搬到其他店铺
        product.setShopId(existed.getShopId());
        productMapper.updateById(product);
        return Result.success(null);
    }

    @Override
    public Result<List<Product>> searchProducts(String keyword) {
        // 搜索限定当前店铺（与 getProductList 同口径，避免跨店数据暴露）
        Long shopId = UserContext.getShopId();
        if (shopId == null) {
            return Result.failure("请先选择店铺");
        }
        LambdaQueryWrapper<Product> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Product::getShopId, shopId.intValue());
        if (keyword == null || keyword.trim().isEmpty()) {
            // 无关键词时返回本店商品（最多 20 条）
            wrapper.last("LIMIT 20");
            return Result.success(productMapper.selectList(wrapper));
        }
        wrapper.and(w -> w.like(Product::getName, keyword)
               .or().like(Product::getDescription, keyword)
               .or().like(Product::getBrand, keyword))
               .last("LIMIT 20");
        return Result.success(productMapper.selectList(wrapper));
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