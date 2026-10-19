package com.amz.service;

import com.amz.model.Rma;
import com.amz.result.PageRequest;
import com.amz.result.PageResult;

/**
 * RMA 退货单维护契约。
 * <p>
 * <b>为什么从 {@code CustomerEmailService} 拆出来</b>：两者只是共用同一个控制器，
 * 领域上并不相干——邮件域管模板/任务/发送，RMA 域管退货申请与状态流转。
 * 合在一个接口上的实际后果是该接口有 17 个方法、实现类 515 行（超 CLAUDE.md 的
 * 500 行上限），且邮件相关的改动会牵动 RMA 的编译单元。
 * <p>
 * <b>不拆 REST 路径</b>：{@code /customer/email/rma/*} 是对外契约的一部分，
 * 拆分的是 Java 侧的类型边界，不是 URL。控制器注入两个服务即可。
 */
public interface RmaService {

    /** 创建退货申请。 */
    Rma createRma(Rma rma);

    /** 退货申请列表（keyset 游标分页）。 */
    PageResult<Rma> listRmas(Long shopId, String status, PageRequest page);

    /** 更新退货状态。 */
    Rma updateRmaStatus(Long rmaId, String status);

    /** 详情（含店铺归属校验）。 */
    Rma getRma(Long rmaId);
}
