/**
 * 承运方式的文案表（从 LogisticsShipmentPanel 拆出，§S33.14）。
 *
 * ## 为什么单独一个模块
 * 与 logisticsStatus.ts / logisticsQuoteLabels.ts 同一理由：测试要**逐字断言**
 * 这张表（本班已在 CJK 文案上凭记忆重写出错四次），而 `<script setup>` 的绑定
 * 不导出，import 只会得到 undefined——第一版 import 就是这么失败的。
 *
 * 目前只有货件面板一个使用者，按「抽象延迟到第二个真实用例」不做多余合并。
 */
export const METHOD_LABELS: Record<string, string> = {
  SEA: '海运',
  AIR: '空运',
  EXPRESS: '快递',
  TRUCK: '卡车'
}

/** 未知方式原样显示，而不是归成「其他」——那会让人以为系统不认识这种方式 */
export const methodLabel = (m?: string) => (m ? METHOD_LABELS[m] || m : '—')
