/**
 * 报价域的文案表（从 LogisticsDashboard.vue 拆出，随 LogisticsQuoteComparePanel 一起）。
 *
 * ## 为什么抽成模块而不是留在组件里
 * 这些表要被测试**逐字断言**（本班已在 CJK 文案上凭记忆重写出错四次，见
 * LogisticsDashboard.test.ts 的回归测试）。而 `<script setup>` 的绑定不导出，
 * 测试 import 不到——那是这条回归测试差点没能成立的原因（第一版 import 得到
 * undefined，`toEqual` 直接报「expected undefined」才发现）。
 *
 * 放独立模块还有第二个好处：同一套口径以后要有第二个使用者（例如报价明细表
 * 也显示服务类型），不用复制一份表，也就不会出现两处各说各话。
 *
 * 与 logisticsTransferStatus.ts 同一处理方式，同一理由。
 */

export const METHOD_LABELS: Record<string, string> = {
  SEA: '海运',
  AIR: '空运',
  EXPRESS: '快递',
  TRUCK: '卡车'
}

/** 比价实际采用的计费口径，必须展示：否则看不懂为什么金额和「重量 × 单价」对不上 */
export const BASIS_LABELS: Record<string, string> = {
  WEIGHT: '按重量',
  VOLUME: '按体积',
  NO_PRICING_DATA: '无可用单价'
}

/** 未知口径原样显示，而不是被映射成某个具体口径——那会给出错的解释 */
export const methodLabel = (m?: string) => (m ? METHOD_LABELS[m] || m : '—')
export const basisLabel = (b?: string) => (b ? BASIS_LABELS[b] || b : '—')
