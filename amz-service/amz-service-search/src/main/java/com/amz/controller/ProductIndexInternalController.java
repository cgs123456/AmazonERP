package com.amz.controller;

import com.amz.annotation.InternalServiceAccess;
import com.amz.model.pojo.ProductDoc;
import com.amz.result.Result;
import com.amz.service.ProductIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品搜索索引的<b>服务间</b>变更通知（product → search）。
 * <p>
 * <b>为什么必须挂在 {@code /internal/**} 下</b>——这是本轮核实出的一个真实越权路径：
 * {@code BaseAuthInterceptor.preHandle} 的判断是
 * <pre>{@code
 * if (!hasUserToken && hasServiceToken && hasInternalServiceAccess(handler)) {
 *     return authenticateInternalService(...);
 * }
 * return authenticateUser(...);   // 有用户 token 就走这里，@InternalServiceAccess 不参与
 * }</pre>
 * 也就是说 {@code @InternalServiceAccess} 只负责<b>允许服务令牌进来</b>，
 * 并不<b>排斥用户 JWT</b>。任何登录用户带自己的 token 打一个
 * 「带 {@code @InternalServiceAccess} 但没有 {@code @RequireRole}」的普通路径端点，
 * 会走用户鉴权分支、注解形同不存在。
 * <p>
 * 三层机制只有 {@code /internal/**} 前缀能同时满足：
 * <ol>
 *   <li>{@code FeignAuthRelayConfig.isInternalPath} 命中 → <b>剥离用户 JWT</b>、强制换服务令牌；</li>
 *   <li>{@code BaseAuthInterceptor.isInternalPath} 命中 → 只走 {@code authenticateInternalService}；</li>
 *   <li>{@code MyGlobalFilter} 命中 → 网关直接 404，公网不可达。</li>
 * </ol>
 * 所以本类与运维类（{@link ProductIndexController}）刻意分成两个控制器、两条路径。
 * <p>
 * <b>变更通知不做店铺归属校验</b>：调用方是 product 服务，文档内容由它给出；
 * 索引侧没有「店铺属于当前用户」的概念（搜索本来就跨店可见）。真正的越权风险在
 * product 侧（谁有权改这个商品），由那边的 {@code @ShopScoped} 与 service 层行级校验负责。
 */
@Slf4j
@RestController
@RequestMapping("/internal/search-index")
public class ProductIndexInternalController {

    @Autowired
    private ProductIndexService productIndexService;

    /**
     * 单条 upsert。
     */
    @InternalServiceAccess("amz-service-product")
    @PostMapping("/upsert")
    public Result<Boolean> upsert(@RequestBody ProductDoc doc) {
        if (doc == null || doc.getId() == null) {
            return Result.failure("文档或 id 不能为空（id 是幂等键）");
        }
        boolean ok = productIndexService.upsert(doc);
        if (!ok) {
            // 明确失败：product 侧据此记 WARN，运维能从日志看到「索引没更新」，
            // 而不是等到运营搜不到商品才发现。
            return Result.failure("索引写入失败（ES 不可用或映射冲突），商品主数据已保存");
        }
        return Result.success(true);
    }

    /**
     * 批量 upsert。
     */
    @InternalServiceAccess("amz-service-product")
    @PostMapping("/upsert-batch")
    public Result<Integer> upsertBatch(@RequestBody List<ProductDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return Result.success(0);
        }
        int written = productIndexService.upsertBatch(docs);
        if (written < docs.size()) {
            return Result.failure("部分文档未写入：提交 " + docs.size() + " 条，写入 " + written + " 条");
        }
        return Result.success(written);
    }

    /**
     * 删除一条索引文档。
     */
    @InternalServiceAccess("amz-service-product")
    @DeleteMapping("/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        if (id == null) {
            return Result.failure("id 不能为空");
        }
        return Result.success(productIndexService.delete(id));
    }
}
