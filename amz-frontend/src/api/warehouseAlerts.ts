import request from './auth'
import type { ApiResponse } from './types'
import { params } from '@/utils/query'

/**
 * 多仓库存与预警接口层（amz-service-logistics，`/logistics/warehouse/**` 的 stock/alert 部分）。
 *
 * 动因（2026-10-03 端点覆盖清点）：`/logistics/warehouse/stock`、`.../alert` 这一组 6 个端点
 * 前端零入口——预警规则、立即检查、按仓聚合的库存快照都在后端能跑，但运营只能 curl。
 * 已有的「海外仓」页只覆盖仓库/库位/出入库，不含这两块。
 *
 * 两条**故意不接**的：
 * - `POST /logistics/warehouse/stock`（手工写库存快照）：数量应由海外仓回传，
 *   在页面上开个手填表单等于允许任何人往快照表里造数，且会覆盖同一 sku+仓 的历史口径；
 * - `PUT /logistics/warehouse/inventory/{id}/location`：已有「海外仓」页在用，不在这里重复。
 *
 * 枚举都不是前端编的：`ALERT_TYPES` 取自 `MultiWarehouseServiceImpl#evaluateAlert` 的 switch，
 * `ALERT_LEVELS` 取自 `checkAlerts` 的计数分支。
 */

export interface WarehouseStock {
  id?: number
  shopId?: number | string
  warehouseId?: number
  warehouseName?: string
  warehouseType?: string
  sku: string
  asin?: string | null
  availableQty?: number | null
  reservedQty?: number | null
  inboundQty?: number | null
  transferOutQty?: number | null
  totalQty?: number | null
  unitCost?: number | string | null
  totalValue?: number | string | null
  lastInboundDate?: string | null
  daysInStock?: number | null
  snapshotTime?: string | null
}

export interface InventoryAlert {
  id?: number
  shopId?: number | string
  sku?: string | null
  warehouseId?: number | null
  alertType: string
  thresholdValue: number | string
  thresholdUnit?: string | null
  alertLevel?: string | null
  /** 只是存字段：全仓没有读取它发通知的地方 */
  notifyChannels?: string | null
  enabled?: boolean | number | null
  description?: string | null
  createTime?: string
  updateTime?: string
}

export interface AlertHit {
  alertId?: number
  alertType?: string
  alertLevel?: string
  description?: string | null
  sku?: string
  warehouseName?: string
  availableQty?: number | null
  daysInStock?: number | null
  totalValue?: number | string | null
}

export interface AlertCheckReport {
  shopId?: number | string
  alertRulesChecked: number
  totalTriggered: number
  critical: number
  warning: number
  info: number
  alerts: AlertHit[]
  /** 扫描有上限（AGGREGATION_SCAN_LIMIT=500）：true 说明这次检查不是全量库存的结论 */
  stocksTruncated?: boolean
  scannedStockCount?: number
}

/** 只有这 5 种类型会被判定；其它值在 evaluateAlert 里 default → 永不触发 */
export const ALERT_TYPES = ['LOW_STOCK', 'STOCKOUT', 'OVERSTOCK', 'AGING', 'NO_MOVEMENT']
export const ALERT_LEVELS = ['CRITICAL', 'WARNING', 'INFO']
/** 后端只认字面量 "DAYS" 走天数分支，其它任何值都按数量比较 */
export const THRESHOLD_UNITS = ['DAYS', 'QTY']




export const listStock = (shopId: number | string,
                          q: { sku?: string; warehouseId?: number | string; size?: number; cursor?: string } = {}) =>
  request.get<void, ApiResponse<WarehouseStock[]>>(`/logistics/warehouse/stock/list/${shopId}`, {
    params: params({ ...q })
  })

export const createAlert = (body: Partial<InventoryAlert>) =>
  request.post<void, ApiResponse<InventoryAlert>>('/logistics/warehouse/alert', body)

export const listAlerts = (shopId: number | string, enabled?: boolean) =>
  request.get<void, ApiResponse<InventoryAlert[]>>(`/logistics/warehouse/alert/list/${shopId}`, {
    params: params({ enabled })
  })

export const toggleAlert = (id: number, enabled: boolean) =>
  request.post<void, ApiResponse<boolean>>(`/logistics/warehouse/alert/${id}/toggle`, null, { params: { enabled } })

/** 只做一次判定并返回结果：不发任何通知，也不写库 */
export const checkAlerts = (shopId: number | string) =>
  request.get<void, ApiResponse<AlertCheckReport>>(`/logistics/warehouse/alert/check/${shopId}`)
