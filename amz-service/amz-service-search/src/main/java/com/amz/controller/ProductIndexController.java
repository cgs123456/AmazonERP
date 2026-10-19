package com.amz.controller;

import com.amz.annotation.OperLog;
import com.amz.annotation.RequireRole;
import com.amz.result.Result;
import com.amz.service.ProductIndexService;
import com.amz.service.impl.ProductIndexRebuildService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品搜索索引的<b>运维</b>端点（人触发，经网关）。
 * <p>
 * <b>为什么不与变更通知放在同一个控制器</b>：通知类端点必须挂在 {@code /internal/**}
 * 才能只接受服务令牌（见 {@link ProductIndexInternalController} 里的三层机制说明），
 * 而 {@code /internal/**} 在网关直接 404——把运维端点也放进去，ADMIN 用户将永远进不来。
 * 两类调用方的身份模型不同（服务 vs 用户），路径与鉴权必须分开。
 * <p>
 * 重建会遍历全量商品主数据并写入 ES，是有成本的动作，因此只允许 ADMIN。
 */
@Slf4j
@RestController
@RequestMapping("/search/index")
public class ProductIndexController {

    @Autowired
    private ProductIndexService productIndexService;

    @Autowired
    private ProductIndexRebuildService rebuildService;

    /**
     * 全量重建索引。
     * <p>
     * 会分页读取全部商品主数据并 upsert，耗时与商品量成正比。
     * 读失败时不动索引（见 {@link ProductIndexRebuildService}）。
     */
    @RequireRole({"ADMIN"})
    @OperLog(module = "商品搜索索引", action = "全量重建", description = "从商品主数据重建搜索索引")
    @PostMapping("/rebuild")
    public Result<ProductIndexRebuildService.RebuildReport> rebuild() {
        try {
            ProductIndexRebuildService.RebuildReport report = rebuildService.rebuildAll();
            if (report.isAborted()) {
                // 中止不是「成功」：把已处理的条数一起返回，调用方据此判断是否重试
                return Result.failure(report.getError() + "（已写入 " + report.getWritten() + " 条）");
            }
            return Result.success(report);
        } catch (Exception e) {
            log.error("商品索引重建失败", e);
            return Result.failure("重建失败：" + e.getMessage());
        }
    }

    /**
     * 当前索引文档数。
     * <p>
     * ES 不可用时返回失败而不是 0——「查不到」与「索引真的空」必须可区分，
     * 否则运维会把 ES 故障读成「还没重建」然后重复执行重建。
     */
    @RequireRole({"VIEWER", "OPERATOR", "ADMIN"})
    @GetMapping("/count")
    public Result<Long> count() {
        long count = productIndexService.count();
        if (count < 0) {
            return Result.failure("ES 不可用，无法读取索引文档数");
        }
        return Result.success(count);
    }
}
