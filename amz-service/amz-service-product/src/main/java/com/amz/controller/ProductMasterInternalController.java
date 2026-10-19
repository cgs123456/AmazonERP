package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.dto.ProductMasterBatchPage;
import com.amz.result.Result;
import com.amz.service.ProductMasterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品主数据的<b>服务间</b>读取端点（search → product，索引全量重建用）。
 * <p>
 * <b>为什么必须挂在 {@code /internal/**} 下</b>：本端点<b>没有店铺维度</b>，
 * 返回的是全库商品。如果放在普通业务路径上，{@code @InternalServiceAccess}
 * 并不能阻止用户 JWT 访问——{@code BaseAuthInterceptor} 只在「没有用户 token」
 * 时才走服务令牌分支，带了用户 token 就一律走用户鉴权，注解不参与判断。
 * 那会让任何登录用户（含 VIEWER）分页拉走整个 {@code amz_product} 表。
 * <p>
 * 挂在 {@code /internal/**} 下同时得到三重保障：
 * <ol>
 *   <li>{@code MyGlobalFilter} 在网关层直接 404，公网不可达；</li>
 *   <li>{@code FeignAuthRelayConfig} 剥离用户 JWT，强制改用服务令牌；</li>
 *   <li>{@code BaseAuthInterceptor} 只接受服务身份，且校验调用方在
 *       {@link InternalServiceAccess} 白名单内。</li>
 * </ol>
 * <p>
 * <b>为什么不复用 {@code /product/master/list/{shopId}}</b>：那个端点按店铺过滤、
 * 有 500 条硬上限、要求用户身份。重建需要跨店铺全量 + keyset 分页 + 服务身份，
 * 三者都对不上。把它改成「无 shopId 时返回全表」会制造一个危险的默认行为。
 */
@Slf4j
@RestController
@RequestMapping("/internal/product-master")
public class ProductMasterInternalController {

    @Autowired
    private ProductMasterService productMasterService;

    /**
     * keyset 分页读取全部商品主数据。
     *
     * @param afterId 上一页最后一条的 id；缺省表示从头开始
     * @param limit   单页条数（服务端钳到安全上限）
     */
    @InternalServiceAccess("amz-service-search")
    @GetMapping("/batch")
    public Result<ProductMasterBatchPage> batch(
            @RequestParam(required = false) Long afterId,
            @RequestParam(defaultValue = "500") Integer limit) {
        try {
            return Result.success(productMasterService.readBatchForIndex(afterId, limit));
        } catch (IllegalArgumentException e) {
            return Result.failure(e.getMessage());
        }
    }
}
