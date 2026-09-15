package com.amz.service;

import com.amz.model.Shipment;
import com.amz.model.TrackingEvent;

import java.util.List;

/**
 * 物流追踪服务接口。
 * <p>
 * <b>归属校验约定：</b>本接口中所有以货件 ID 为入参的方法，都必须在实现内校验该货件
 * 是否属于当前用户授权店铺（依据 {@code UserContext}）。原因是货件 ID 直接来自请求路径，
 * 若只按 ID 取数就等于把「换一个数字就能读他店数据」的口子留在端点里；
 * 而若改为要求额外传入 shopId，则会破坏既有内部调用（AI 工具的 Feign 客户端）的签名。
 * 因此校验下沉到实现层，对调用方透明。
 */
public interface LogisticsService {

    /**
     * 创建头程物流单 / FBA 货件。
     */
    Shipment createShipment(Shipment shipment);

    /**
     * 查询店铺货件列表。
     */
    List<Shipment> listShipments(Long shopId, String status);

    /**
     * 同步货件状态（拉取承运商最新轨迹，更新货件状态）。
     * <p>
     * 轨迹落库经统一核心处理：状态映射、幂等合并、取数时间刷新与「外部导入」入口完全一致。
     */
    Shipment syncShipmentStatus(Long shipmentId);

    /**
     * 查询货件的完整轨迹（用于前端轨迹可视化）。
     * 返回按时间正序排列的轨迹点列表（含经纬度）。
     */
    List<TrackingEvent> getTrackingTimeline(Long shipmentId);

    /**
     * 手工关闭货件。
     * <p>
     * 存在的意义是补齐状态机的终点：{@code CLOSED} 是终态，但没有任何自动流程会产出它——
     * 承运商不提供「本次头程业务已了结」这类信息。对最终确认不再送货、货损核销、
     * 或长期挂起需人工结案的货件，缺少手工关单会让它永远停留在在途/异常状态，
     * 既污染看板的在途计数，也被定时任务持续轮询。
     *
     * @param shipmentId 货件 ID
     * @param shopId     店铺 ID，用于显式校验归属（可为 null，此时仅依赖 UserContext 校验）
     * @return 关闭后的货件
     */
    Shipment closeShipment(Long shipmentId, Long shopId);
}
