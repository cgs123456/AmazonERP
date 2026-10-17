/**
 * 货件状态的中文名与状态色（从 LogisticsDashboard.vue 拆出，§S33.12）。
 *
 * ## 为什么抽出来
 * 拆概览面板时发现这两个函数有**三个**使用者：概览的状态分布、待处理 tab 的
 * 告警表、在途货件 tab 的货件表。复制一份进概览组件，就会出现「同一张单在
 * 概览显示运输中、在货件表显示在途」——系统里两个真相，而没有任何测试会红
 * （本班已在 CJK 文案上凭记忆重写出错四次，见 LogisticsDashboard.test.ts）。
 *
 * 与 logisticsTransferStatus.ts / logisticsQuoteLabels.ts 同一处理方式、同一理由。
 * 放独立 .ts 而非留在 `<script setup>` 里：setup 的绑定不导出，测试 import
 * 只会得到 undefined。
 */

export const STATUS_LABELS: Record<string, string> = {
  CREATED: '已创建',
  IN_TRANSIT: '运输中',
  CUSTOMS: '清关中',
  DELIVERED: '已送达',
  RECEIVED: 'FBA已入库',
  CLOSED: '已关闭',
  DELAYED: '延误',
  EXCEPTION: '异常',
  // 轨迹点状态（与货件状态命名不同，这里一并兜底展示）
  DEPARTED: '已起运',
  CUSTOMS_CLEARANCE: '清关中',
  ARRIVED: '已到港',
  OUT_FOR_DELIVERY: '派送中'
}

export const statusLabel = (s?: string) => (s ? STATUS_LABELS[s] || s : '—')

/** 状态 → 语义配色。延误/异常为红，正常推进为绿/中性，避免全表一个颜色看不出重点 */
export const statusTone = (s?: string) => {
  switch (s) {
    case 'DELAYED':
    case 'EXCEPTION':
      return 'tone-danger'
    case 'DELIVERED':
    case 'RECEIVED':
      return 'tone-success'
    case 'IN_TRANSIT':
    case 'CUSTOMS':
    case 'DEPARTED':
    case 'CUSTOMS_CLEARANCE':
    case 'ARRIVED':
    case 'OUT_FOR_DELIVERY':
      return 'tone-info'
    default:
      return 'tone-muted'
  }
}
