package com.amz.dto;

import lombok.Data;

import java.util.List;

/**
 * 外部导入的一条运单轨迹（一个货件 + 其若干轨迹点）。
 * <p>
 * 以「运单」为批次分组而非把轨迹点铺平成一张大表，是因为落库需要先定位货件再写入轨迹点，
 * 分组后每组的归属判定只做一次；同时也让「未匹配」的粒度落在运单上——
 * 使用者看到的是「这 3 个运单没有对应货件」，而不是「这 17 条轨迹有 17 个错误」，
 * 后者无法据此补录主单。
 */
@Data
public class ImportTrackingDTO {

    /** 货件编号，与 trackingNo 至少提供一个；两者都给时优先按货件编号匹配 */
    private String shipmentNo;

    /** 主运单号，作为匹配货件的退化依据 */
    private String trackingNo;

    /**
     * 该运单的轨迹点列表。
     * <p>
     * 可传全量历史，无需自行裁剪成「仅新增」——落库按 (状态, 时间) 指纹做增量合并，
     * 已存在的点会计入 skipped 而不是重复插入。这使「每次导出全量再导入」成为合法用法，
     * 使用侧不必维护增量水位。
     */
    private List<ImportTrackingEventDTO> events;
}
