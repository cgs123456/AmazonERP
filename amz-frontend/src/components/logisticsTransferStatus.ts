/**
 * 调拨单状态的中文名与状态色（从 LogisticsTransferPanel 抽出为共享模块）。
 *
 * ## 为什么抽出来而不是各自复制
 * 拆出 LogisticsTransferRiskPanel 后，「风险清单」和「调拨单列表」要把状态码
 * 显示成中文：一个标在风险行的「状态」列，一个标在列表行的状态标签上。
 * 两张表不一致的后果是**同一张单在同一个 tab 里显示成两个状态名**——用户会
 * 认为系统出了两个真相。本班已经四次在 CJK 文案上凭记忆重写出错
 * （详见 LogisticsDashboard.test.ts 的回归测试），复制一份表就多一个出错点。
 *
 * 因此这里放一份，两个组件都 import。
 */

export const TRANSFER_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已审批待发出',
  IN_TRANSIT: '运输中',
  RECEIVED: '已收货',
  CANCELLED: '已取消'
}

/** 状态码 → 中文名；未知状态原样显示（宁可露出原始码，也不要显示成别的状态）。 */
export const transferStatusLabel = (s?: string) => (s ? TRANSFER_STATUS_LABELS[s] || s : '—')

/** 状态码 → 状态标签色（对应共享样式表的 .status-tag.tone-*）。 */
export const transferStatusTone = (s?: string) => {
  switch (s) {
    case 'CANCELLED':
      return 'tone-muted'
    case 'RECEIVED':
      return 'tone-success'
    case 'IN_TRANSIT':
      return 'tone-info'
    case 'APPROVED':
    case 'PENDING_APPROVAL':
      return 'tone-warn'
    default:
      return 'tone-muted'
  }
}
