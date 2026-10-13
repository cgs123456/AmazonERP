package com.amz.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存元数据 CSV 导入报告（A6 第二个域）。
 *
 * <p>与第一个域的报告刻意不共用一个类：字段集不同（本域<b>没有</b> inserted——
 * 库存行只能由单据产生，CSV 不新建行）。强行合并成一个「通用导入报告」会让
 * insertedRows 对库存永远是 0，摆着比缺着更容易误读。第二个真实用例仍未到，
 * 报告抽象留到那时再抽（与 CsvImportUtil 的复用节奏一致）。
 */
@Data
public class InventoryCsvImportReport {

    /** 本次总数据行数（含坏行）。 */
    private int totalRows;

    /** 实际更新的行数。 */
    private int updatedRows;

    /** 跳过的行数（整行元数据与键值之外的列都空 → 等于没改，不算失败）。 */
    private int skippedRows;

    /** 失败行数 = errors 大小。 */
    private int failedRows;

    /** 行级错误：文件行号（1 基，含表头行）→ 原因（定位不到/不唯一/格式错/数量列被拒）。 */
    private Map<Integer, String> errors = new LinkedHashMap<>();

    /** 回显识别到的表头，列名对不上时先核这里。 */
    private List<String> headers = new ArrayList<>();
}
