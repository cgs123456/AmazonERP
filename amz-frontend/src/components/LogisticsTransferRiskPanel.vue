<script setup lang="ts">
/**
 * 调拨风险预警（从 LogisticsTransferPanel 再拆出，§S33）。
 *
 * ## 为什么单独拆
 * 原「调拨面板」混了两件事，各自独立演化：**风险预警**（看板 + 待跟进清单，
 * 只读，面向「哪张单卡住了」）和**单据管理**（调拨单列表 + 审批/发出/收货状态机，
 * 面向「把这张单推到下一步」）。前者没有写操作，后者全是写操作；合在一个件里，
 * 「改一个审批动作」和「加一张 KPI」会互相挤占同一个文件，读者也无法一眼看出
 * 这块面板里哪些地方会改数据。拆开后两块都在 500 行以内。
 *
 * ## 职责边界
 * - 本组件只收 board prop 并渲染，**不自己拉数据、不写操作**；
 * - loader 归 LogisticsTransferPanel（它还要用同一份 board 算 tab 角标），
 *   失败走页级错误区那条既有通道；
 * - 因此本组件没有 emit——它确实一个事件都不需要。
 */
import Icon from './Icon.vue'
import type { TransferBoard } from '../api/logistics'

import { transferStatusLabel } from './logisticsTransferStatus'

defineProps<{
  /** 调拨看板数据（含 risks 清单），由父面板拉取 */
  board: TransferBoard | null
}>()

/* ---------- 文案与格式化（从父面板原样搬来，未改一字） ---------- */

const TRANSFER_RISK_LABELS: Record<string, string> = {
  STALE_IN_TRANSIT: '在途超期',
  PENDING_APPROVAL_TOO_LONG: '审批超期',
  MISSING_TRACKING_NO: '缺物流单号'
}
const transferRiskLabel = (t?: string) => (t ? TRANSFER_RISK_LABELS[t] || t : '—')

const severityLabel = (s: string) => (s === 'HIGH' ? '高' : s === 'MEDIUM' ? '中' : '低')
const severityTone = (s: string) =>
  s === 'HIGH' ? 'tone-danger' : s === 'MEDIUM' ? 'tone-warn' : 'tone-muted'

const fmtAmount = (v?: number | null) =>
  v === null || v === undefined ? '—' : v.toFixed(2)
</script>

<template>
    <div v-if="board?.warnings?.length" class="warn-banner">
      <Icon icon="mdi:information-outline" width="16" class="wb-icon" />
      <div class="wb-body">
        <div v-for="(w, i) in board.warnings" :key="i">{{ w }}</div>
      </div>
    </div>

    <div class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-icon"><Icon icon="mdi:swap-horizontal" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ board?.total ?? 0 }}</div>
          <div class="kpi-label">调拨单总数</div>
          <div class="kpi-sub">仓间库存移动</div>
        </div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': (board?.pendingApproval ?? 0) > 0 }">
        <div class="kpi-icon tone-warn"><Icon icon="mdi:account-clock-outline" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ board?.pendingApproval ?? 0 }}</div>
          <div class="kpi-label">待审批</div>
          <div class="kpi-sub">草稿 + 待审批</div>
        </div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': (board?.approvedNotShipped ?? 0) > 0 }">
        <div class="kpi-icon tone-warn"><Icon icon="mdi:package-variant-closed" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ board?.approvedNotShipped ?? 0 }}</div>
          <div class="kpi-label">已批待发出</div>
          <div class="kpi-sub">货还在源仓</div>
        </div>
      </div>
      <div class="kpi-card" :class="{ 'kpi-alert': (board?.staleInTransit ?? 0) > 0 }">
        <div class="kpi-icon tone-danger"><Icon icon="mdi:truck-alert-outline" width="24" /></div>
        <div class="kpi-info">
          <div class="kpi-value">{{ board?.staleInTransit ?? 0 }}</div>
          <div class="kpi-label">在途卡单</div>
          <div class="kpi-sub">
            超过 {{ board?.staleThresholdDays ?? 7 }} 天未收货
          </div>
        </div>
      </div>
    </div>

    <div class="mini-grid">
      <div class="mini-card">
        <div class="mini-value">{{ board?.inTransit ?? 0 }}</div>
        <div class="mini-label">运输中</div>
        <div class="mini-hint">正常在途的单据</div>
      </div>
      <div class="mini-card">
        <div class="mini-value">{{ board?.received ?? 0 }}</div>
        <div class="mini-label">已收货</div>
        <div class="mini-hint">已完成的调拨</div>
      </div>
      <div class="mini-card">
        <div class="mini-value">{{ board?.cancelled ?? 0 }}</div>
        <div class="mini-label">已取消</div>
        <div class="mini-hint">驳回或作废</div>
      </div>
      <div class="mini-card">
        <div class="mini-value">{{ fmtAmount(board?.totalShippingCost) }}</div>
        <div class="mini-label">调拨运费合计</div>
        <div class="mini-hint">全部调拨单累计</div>
      </div>
    </div>

    <div class="chart-card">
      <h3>待跟进调拨单</h3>
      <p class="card-desc">
        状态字段本身只说明「在途」，看不出已经卡了多久——因此这里按在途天数挑出卡单，
        并区分「审批卡住」与「货卡在路上」两类，处理动作完全不同。
      </p>
      <table class="data-table">
        <thead>
          <tr>
            <th>级别</th>
            <th>类型</th>
            <th>调拨单号</th>
            <th>货</th>
            <th>数量</th>
            <th>状态</th>
            <th>已停留</th>
            <th>承运商 / 单号</th>
            <th>说明与建议</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!board?.risks?.length">
            <td colspan="9" class="empty-row">当前没有需要跟进的调拨单</td>
          </tr>
          <tr v-for="r in board?.risks || []" :key="r.type + '-' + r.id">
            <td>
              <span class="status-tag" :class="severityTone(r.severity)">
                {{ severityLabel(r.severity) }}
              </span>
            </td>
            <td>{{ transferRiskLabel(r.type) }}</td>
            <td class="mono">{{ r.transferNo }}</td>
            <td>
              <div>{{ r.asin }}</div>
              <div class="sub-line">{{ r.sku }}</div>
            </td>
            <td>{{ r.quantity }}</td>
            <td>{{ transferStatusLabel(r.status) }}</td>
            <td>{{ r.daysSinceCreated != null ? r.daysSinceCreated + ' 天' : '—' }}</td>
            <td class="mono">
              {{ r.carrier || '—' }}
              <div class="sub-line">{{ r.trackingNo || '未登记' }}</div>
            </td>
            <td class="hint-cell">
              <div class="hint-msg">{{ r.message }}</div>
              <div class="hint-action">建议：{{ r.actionHint }}</div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
</template>

<style scoped>
/* 不定义任何样式：kpi-* / mini-* / chart-card / warn-banner / status-tag /
 * sub-line / hint-* / mono / empty-row / data-table 全部来自共享样式表。
 * 由 tools/release/test_css_scope_parity.py 盯住。
 */
</style>
