import request from './auth'
import type { ApiResponse } from './types'
import { getShops } from '../utils/shop'

// 库存列表项（前端展示模型，由后端 FbaInventory 行 + 补货建议 join 而成）
export interface InventoryItem {
  sku: string
  asin: string
  shop: string
  stock: number
  dailySales: number
  days: number
  level: 'urgent' | 'risk' | 'healthy' | 'overstock'
  levelText: string
  suggestQty: number
}

// 库存健康度统计
export interface InventoryHealth {
  urgent: number
  risk: number
  healthy: number
  overstock: number
}

// 后端 FbaInventory 行（GET /spapi/inventory/health/{shopId} 元素结构）
export interface FbaInventoryRow {
  sku: string
  asin?: string
  shopId?: number | string
  availableQuantity?: number
  avg7Days?: number | string
  avg30Days?: number | string
  daysOfSupply?: number | string
  healthStatus?: string
}

// 后端补货建议行（GET /spapi/replenish/list/{shopId} 元素结构）
export interface ReplenishSuggestionRow {
  sku: string
  asin?: string
  statDate?: string
  suggestedReplenishQty?: number
  urgencyLevel?: string
}

const toNum = (v: unknown): number => {
  const n = Number(v)
  return isNaN(n) ? 0 : n
}

// 后端 healthStatus（URGENT/AT_RISK/HEALTHY/OVERSTOCK/STOCKOUT）→ 前端四态
export const mapHealthLevel = (status?: string): { level: InventoryItem['level']; levelText: string } => {
  switch ((status || '').toUpperCase()) {
    case 'URGENT':
      return { level: 'urgent', levelText: '紧急' }
    case 'AT_RISK':
      return { level: 'risk', levelText: '风险' }
    case 'OVERSTOCK':
      return { level: 'overstock', levelText: '滞销' }
    case 'STOCKOUT':
      return { level: 'urgent', levelText: '断货' }
    case 'HEALTHY':
      return { level: 'healthy', levelText: '健康' }
    default:
      return { level: 'risk', levelText: '未知' }
  }
}

// FbaInventory 行 → 前端展示行（suggestQty 后续由补货建议 join 回填）
export const mapInventoryRow = (row: FbaInventoryRow, shopName: string): InventoryItem => {
  const { level, levelText } = mapHealthLevel(row.healthStatus)
  return {
    sku: row.sku,
    asin: row.asin || '-',
    shop: shopName,
    stock: Math.max(0, Math.round(toNum(row.availableQuantity))),
    dailySales: toNum(row.avg7Days ?? row.avg30Days ?? 0),
    days: toNum(row.daysOfSupply ?? 0),
    level,
    levelText,
    suggestQty: 0
  }
}

// 按 SKU join 补货建议（同 SKU 取 statDate 最新一条的建议量）
export const joinSuggestQty = (
  items: InventoryItem[],
  suggestions: ReplenishSuggestionRow[]
): InventoryItem[] => {
  const latest = new Map<string, ReplenishSuggestionRow>()
  for (const s of suggestions || []) {
    if (!s || !s.sku) continue
    const prev = latest.get(s.sku)
    if (!prev || String(s.statDate || '') >= String(prev.statDate || '')) {
      latest.set(s.sku, s)
    }
  }
  return items.map((item) => ({
    ...item,
    suggestQty: Math.max(0, Math.round(toNum(latest.get(item.sku)?.suggestedReplenishQty)))
  }))
}

// 由展示行派生健康度计数（与 health 端点口径一致）
export const deriveHealthCounts = (items: InventoryItem[]): InventoryHealth => {
  const counts: InventoryHealth = { urgent: 0, risk: 0, healthy: 0, overstock: 0 }
  for (const item of items || []) {
    if (item && counts[item.level] !== undefined) counts[item.level] += 1
  }
  return counts
}

const resolveShopName = (shopId: number | string): string => {
  const hit = getShops().find((s) => String(s.id) === String(shopId))
  return hit?.name || `店铺 ${shopId}`
}

// 在途健康行请求去重：列表与计数并行调用时共享同一 GET，避免一次进页打 3 遍。
// 仅缓存进行中的 Promise（settled 即删），不缓存结果—— sequential 刷新永远走网络。
const inflightHealth = new Map<string, Promise<ApiResponse<FbaInventoryRow[]>>>()
const fetchHealth = (shopId: number | string): Promise<ApiResponse<FbaInventoryRow[]>> => {
  const key = String(shopId)
  const hit = inflightHealth.get(key)
  if (hit) return hit
  const p = request
    .get<void, ApiResponse<FbaInventoryRow[]>>(`/spapi/inventory/health/${shopId}`)
    .finally(() => {
      if (inflightHealth.get(key) === p) inflightHealth.delete(key)
    })
  inflightHealth.set(key, p)
  return p
}

// 获取店铺库存列表。
// 后端无独立列表端点：以 GET /spapi/inventory/health/{shopId} 的 FbaInventory 行为准，
// 再 join GET /spapi/replenish/list/{shopId} 回填建议补货量。
export const getInventoryList = async (shopId: number | string) => {
  const [healthRes, replenishRes] = await Promise.all([
    fetchHealth(shopId),
    request.get<void, ApiResponse<ReplenishSuggestionRow[]>>(`/spapi/replenish/list/${shopId}`)
  ])
  if (healthRes?.code !== 200 || !Array.isArray(healthRes.data)) {
    return { ...healthRes, data: [] as InventoryItem[] }
  }
  const shopName = resolveShopName(shopId)
  const items = (healthRes.data || []).map((row) => mapInventoryRow(row, shopName))
  const suggestions = replenishRes?.code === 200 && Array.isArray(replenishRes.data) ? replenishRes.data : []
  return { ...healthRes, data: joinSuggestQty(items, suggestions) }
}

// 获取库存健康度（由 health 行派生计数，与列表口径一致；与 getInventoryList 共享在途请求）
export const getInventoryHealth = async (shopId: number | string) => {
  const res = await fetchHealth(shopId)
  if (res?.code !== 200 || !Array.isArray(res.data)) {
    return { ...res, data: { urgent: 0, risk: 0, healthy: 0, overstock: 0 } as InventoryHealth }
  }
  const shopName = resolveShopName(shopId)
  const items = (res.data || []).map((row) => mapInventoryRow(row, shopName))
  return { ...res, data: deriveHealthCounts(items) }
}
