<script setup lang="ts">
/**
 * 待处理告警清单（从 LogisticsDashboard.vue 拆出，§S33.13）。
 *
 * ## 这块为什么好拆：只读 + 三个转发
 * 表格只渲染 `alerts` 数组，三个按钮（同步 / 轨迹 / 关单）都是转发给页壳的
 * 既有 handler。**本组件不持有任何状态、不发自己的事件语义**——
 * 它就是把一张表和三个既有动作连起来。
 *
 * ## 但 handler 没有搬进来
 * `doSync` / `openTracking` / `doClose` 同样服务于在途货件 tab（那边也各有一个
 * 同步/轨迹/关单按钮）。搬进来就要在告警面板里也放一份，或者向上提一层，
 * 两者都会让「关单后为什么重拉了 overview」这条逻辑出现在两个地方。
 * 而且这三个 handler 的成功路径都要动页壳的 overview / shipments / trend——
 * 那更是页壳的职责。所以留在页壳，本组件只发事件。
 *
 * ## 严重度与类型文案只服务这块
 * `severityLabel` / `severityTone` / `ALERT_TYPE_LABELS` 全文件只有这一个使用者，
 * 随组件搬来。**状态中文名不搬**：`statusLabel` 有三个使用者（概览状态分布、
 * 待处理这张表、在途货件表），已抽成 logisticsStatus.ts 共用。
 */
import type { ShipmentAlert } from '../api/logistics'
import { statusLabel } from './logisticsStatus'

const props = defineProps<{
  /** 当前是否激活（页壳的 tab 是不是本面板） */
  active: boolean
  /** 待处理告警列表 */
  alerts: ShipmentAlert[]
  /** 页级操作互斥标记（同步/关单进行中时禁用按钮） */
  busy: boolean
}>()

const emit = defineEmits<{
  (e: 'sync', shipmentId: number): void
  (e: 'track', shipmentId: number): void
  (e: 'close', shipmentId: number): void
}>()

/* ---------- 告警专属文案（从页壳原样搬来，未改一字） ---------- */

const ALERT_TYPE_LABELS: Record<string, string> = {
  DELAYED: '延误',
  EXCEPTION: '异常',
  ETA_OVERDUE: '超期未标',
  ETA_APPROACHING: '临近到港',
  STALE_DATA: '取数过期',
  MISSING_TRACKING_NO: '缺运单号'
}

const alertTypeLabel = (t: string) => ALERT_TYPE_LABELS[t] || t

const severityLabel = (s: string) => (s === 'HIGH' ? '高' : s === 'MEDIUM' ? '中' : '低')
const severityTone = (s: string) => (s === 'HIGH' ? 'tone-danger' : s === 'MEDIUM' ? 'tone-warn' : 'tone-muted')
</script>

<template>
  <div v-if="props.active">

            <div class="table-card">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>级别</th>
                    <th>类型</th>
                    <th>货件号</th>
                    <th>承运商</th>
                    <th>运单号</th>
                    <th>状态</th>
                    <th>ETA</th>
                    <th>超期</th>
                    <th>说明与建议</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!alerts.length">
                    <td colspan="10" class="empty-row">当前没有需要人工处理的货件</td>
                  </tr>
                  <tr v-for="a in alerts" :key="a.type + '-' + a.shipmentId">
                    <td>
                      <span class="status-tag" :class="severityTone(a.severity)">
                        {{ severityLabel(a.severity) }}
                      </span>
                    </td>
                    <td>{{ alertTypeLabel(a.type) }}</td>
                    <td class="mono">{{ a.shipmentNo }}</td>
                    <td>{{ a.carrier || '—' }}</td>
                    <td class="mono">{{ a.masterTrackingNo || '—' }}</td>
                    <td>{{ statusLabel(a.status) }}</td>
                    <td class="mono">{{ a.eta || '—' }}</td>
                    <td>{{ a.daysOverdue !== null && a.daysOverdue > 0 ? a.daysOverdue + ' 天' : '—' }}</td>
                    <td class="hint-cell">
                      <div class="hint-msg">{{ a.message }}</div>
                      <div class="hint-action">建议：{{ a.actionHint }}</div>
                    </td>
                    <td class="ops">
                      <button class="link-btn" :disabled="busy" @click="emit('sync', a.shipmentId)">同步</button>
                      <button class="link-btn" :disabled="busy" @click="emit('track', a.shipmentId)">轨迹</button>
                      <button class="link-btn" :disabled="busy" @click="emit('close', a.shipmentId)">关单</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          
  </div>
</template>

<style scoped>
/* 不定义任何样式：table-card / data-table / status-tag / mono / empty-row /
 * ops / link-btn / hint-cell / hint-msg / hint-action 全部来自共享样式表与
 * src/style.css。由 tools/release/test_css_scope_parity.py 盯住。
 */
</style>
