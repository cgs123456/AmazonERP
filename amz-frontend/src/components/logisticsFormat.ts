/**
 * 物流看板共用的展示格式化（从 LogisticsDashboard.vue 拆出）。
 *
 * ## 为什么抽出来
 * `fmtRate` 有两个使用者：报价覆盖看板（搬进 LogisticsQuoteBoardPanel）与
 * 承运商表现 tab（仍在页壳）。把它复制一份到面板里，以后改口径（例如从
 * 一位小数改成两位）就要改两处，而两处不一致不会让任何测试变红——
 * 同一个延误率在两张表上显示成 25.0% 和 25.3% 是查不出来的。
 *
 * 所以共用的放这里。只服务报价域的那几个（priceRange / transitRange）留在了
 * LogisticsQuoteBoardPanel.vue 里——它们目前只有那一个使用者，按「抽象延迟到
 * 第二个真实用例出现」不提前抽。文案表在 logisticsQuoteLabels.ts，理由见该文件头。
 */

/** 比率（0~1）→ 百分比；null / undefined 表示无样本，显示占位而非 0% */
export const fmtRate = (v?: number | null) =>
  v === null || v === undefined ? '—' : (v * 100).toFixed(1) + '%'
