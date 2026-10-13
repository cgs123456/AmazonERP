package com.amz.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供货价 CSV 导入报告（A6 第三个域）。
 *
 * <p>与库存元数据报告同形（都 update-only、无 insertedRows），但仍是<b>各自独立的 DTO</b>：
 * 报告的字段是对外契约的一部分，商品域有 insertedRows 而这两个没有——强行并成一个带
 * insertedRows 的通用报告，会让 update-only 域摆着一个恒为 0 的字段误导使用者
 * （「新增 0」被读成「这次什么都没新增」，其实是不支持新增）。
 */
@Data
public class SupplierPriceCsvImportReport {

    private int totalRows;
    private int updatedRows;
    private int skippedRows;
    private int failedRows;
    /** 文件行号（1 基、含表头行）→ 原因。 */
    private Map<Integer, String> errors = new LinkedHashMap<>();
    private List<String> headers = new ArrayList<>();
}
