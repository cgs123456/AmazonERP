package com.amz.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品 CSV 导入报告（A6）。
 *
 * <p>与 {@code logistics} 的 {@code ImportReport} 同构但独立定义：搬那个类要改
 * 现有 import 路径，而第二个真实导入域出现前不该为「以后可能复用」付出这个成本
 * （到时有真实用例再统一抽象）。
 *
 * <p>三组信息，缺一组就让导入结果不可操作：
 * <ol>
 *   <li><b>统计</b>（inserted/updated/skipped/failed）——判断本次导入是否达到预期；</li>
 *   <li><b>errors</b>（文件行号 → 原因）——直接对应到文件里的具体行，使用者能跳过去改；</li>
 *   <li><b>headers</b>——把识别到的表头回显，列名对不上时使用者能立刻看出是
 *       模板错了还是自己加错了列。</li>
 * </ol>
 * {@code skipped}（空行）与 {@code failed}（校验不过）刻意分开：混在一起会让
 * 「文件末尾有空行」看起来像出了故障。
 */
@Data
public class ProductCsvImportReport {

    /** 本次总数据行数（含坏行）。 */
    private int totalRows;

    /** 新建行数。 */
    private int insertedRows;

    /** 更新行数（同一 (shopId, sku, marketplaceId) 已存在）。 */
    private int updatedRows;

    /** 跳过的空行数（整行无任何有效字段）。 */
    private int skippedRows;

    /** 失败行数 = {@link #errors} 的大小。 */
    private int failedRows;

    /** 行级错误：文件行号（1 基，含表头行）→ 原因。 */
    private Map<Integer, String> errors = new LinkedHashMap<>();

    /** 识别到的表头（回显，便于排查列名不匹配）。 */
    private List<String> headers = new ArrayList<>();
}
