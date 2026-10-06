import request from './auth'
import type { ApiResponse } from './types'
import { params } from '@/utils/query'

/**
 * 智能审单接口层（amz-service-order，`/order/audit/**`）。
 *
 * 动因（2026-10-02 功能覆盖清点 N2）：审单 9 个端点零前端入口，规则只能靠 curl 维护，
 * 运营看到的「已拦截/已拆分」全部无从核对。
 *
 * 三条必须在界面上说清的边界（都不是前端能补的）：
 * 1. 审单只产出 verdict 与建议，不改订单数据。`MERGE`/`SPLIT` 是规则建议——
 *    系统里没有合并/拆单实现，`amz_order_split_log` 全仓零插入点，所以拆分日志只读且必为空。
 * 2. `shipping_address` 这个条件字段永远判不出来（Order 模型没有地址字段），
 *    DDL 预置的 PO Box / APO / 同地址三条规则都会落进 unevaluatedRules，把 verdict 抬到 REVIEW。
 * 3. 路由建议只给仓库**类型**：`warehouse_name`/`warehouse_id` 由物流模块确认后才回填，
 *    后端已停止把 `country + "-FBA-Warehouse"` 这类拼出来的名字写库。
 *
 * 下面的枚举不是前端编的：ACTION 取自 `OrderAuditServiceImpl#auditOrder` 的 switch，
 * FIELD 取自 `extractFieldValue`，OP 取自 `evaluateCondition`。
 */

export interface OrderAuditRule {
  id?: number
  shopId?: number | string
  ruleName: string
  ruleType: string
  conditionField: string
  conditionOp: string
  conditionValue: string
  action: string
  actionParams?: string | null
  priority?: number | null
  enabled?: boolean | number | null
  description?: string | null
  createTime?: string
  updateTime?: string
}

export const RULE_ACTIONS = ['BLOCK', 'FLAG', 'ALERT', 'MERGE', 'SPLIT']
/** MERGE/SPLIT 只给建议，不会改变订单数据 */
export const ADVISORY_ACTIONS = ['MERGE', 'SPLIT']
export const RULE_FIELDS = [
  'amazon_order_id', 'order_status', 'fulfillment_channel',
  'final_price', 'marketplace_id', 'buyer_name', 'shipping_address'
]
/** 后端取不到值的字段：规则永远不会命中，只会进 unevaluatedRules */
export const UNRESOLVED_FIELDS = ['shipping_address']
export const RULE_OPS = ['EQ', 'NEQ', 'CONTAINS', 'GT', 'LT', 'GTE', 'LTE', 'REGEX']

export interface AuditAlert {
  ruleId?: number
  ruleName?: string
  ruleType?: string
  action?: string
  description?: string | null
}

export interface UnevaluatedRule {
  ruleId?: number
  ruleName?: string
  action?: string
  conditionField?: string
  conditionOp?: string
  reason?: string
}

export interface AuditResult {
  orderId?: number | string
  shopId?: number | string
  verdict: 'PASS' | 'REVIEW' | 'BLOCKED' | string
  alerts: AuditAlert[]
  actions: string[]
  alertCount: number
  unevaluatedRules: UnevaluatedRule[]
  unevaluatedCount: number
  advisoryActions?: string[]
  advisoryNote?: string
  auditTime?: string
}

/** 审单入参：只有这些字段能被规则看到（extractFieldValue 认的字段集） */
export interface AuditOrderInput {
  amazonOrderId?: string
  buyerName?: string
  orderStatus?: string
  fulfillmentChannel?: string
  finalPrice?: number | string | null
  marketplaceId?: string
}

export interface ShipmentRouting {
  id?: number
  shopId?: number | string
  amazonOrderId?: string
  sku?: string
  asin?: string | null
  quantity?: number | null
  warehouseId?: number | null
  warehouseName?: string | null
  warehouseType?: string | null
  carrierName?: string | null
  trackingNo?: string | null
  shippingCost?: number | string | null
  selectedReason?: string | null
  routeTime?: string
}

export interface OrderSplitLog {
  id?: number
  shopId?: number | string
  originalOrderId?: string
  splitOrderId?: string
  splitReason?: string | null
  splitItems?: string | null
  operator?: string | null
  splitTime?: string
}


/* ==================== 规则 ==================== */

export const listRules = (shopId: number | string, enabled?: boolean) =>
  request.get<void, ApiResponse<OrderAuditRule[]>>(`/order/audit/rule/list/${shopId}`, {
    params: params({ enabled })
  })

export const createRule = (body: Partial<OrderAuditRule>) =>
  request.post<void, ApiResponse<OrderAuditRule>>('/order/audit/rule', body)

export const updateRule = (id: number, body: Partial<OrderAuditRule>) =>
  request.put<void, ApiResponse<OrderAuditRule>>(`/order/audit/rule/${id}`, body)

export const toggleRule = (id: number, enabled: boolean) =>
  request.post<void, ApiResponse<boolean>>(`/order/audit/rule/${id}/toggle`, null, { params: { enabled } })

export const deleteRule = (id: number) =>
  request.delete<void, ApiResponse<boolean>>(`/order/audit/rule/${id}`)

/* ==================== 审单执行 ==================== */

export const auditOrder = (shopId: number | string, body: AuditOrderInput) =>
  request.post<void, ApiResponse<AuditResult>>(`/order/audit/order/${shopId}`, body)

export const batchAudit = (shopId: number | string, body: AuditOrderInput[]) =>
  request.post<void, ApiResponse<AuditResult[]>>(`/order/audit/batch/${shopId}`, body)

/* ==================== 发货路由 ==================== */

export const routeOrder = (shopId: number | string, q: {
  amazonOrderId: string; sku: string; asin?: string; quantity?: number; country?: string
}) =>
  request.get<void, ApiResponse<ShipmentRouting>>(`/order/audit/route/${shopId}`, { params: params({ ...q }) })

/* ==================== 拆分日志（只读） ==================== */

export const listSplitLogs = (shopId: number | string, originalOrderId?: string) =>
  request.get<void, ApiResponse<OrderSplitLog[]>>(`/order/audit/split-log/list/${shopId}`, {
    params: params({ originalOrderId })
  })
