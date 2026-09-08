import { describe, it, expect } from 'vitest'
import { mapProfitReport } from '@/api/profit'
import { mapHealthLevel, mapInventoryRow, joinSuggestQty, deriveHealthCounts } from '@/api/inventory'
import { mapOrderRow, mapOrderStatus } from '@/api/order'

// 后端原始结构 → 前端展示模型的纯函数映射测试
// （对齐后端 Controller 实际返回：OrderController#profitReport、
// InventoryController#health、ReplenishmentController#list）
describe('mapProfitReport（后端扁平结构 → { summary, rows }）', () => {
  const raw = {
    totalRevenue: 12456.8,
    totalCost: 7234.2,
    totalProfit: 5222.6,
    margin: 0.419,
    reports: [
      {
        statDate: '2026-06-01',
        revenue: 3200,
        productCost: 1600,
        referralFee: 480,
        adCost: 320,
        fbaFulfillmentFee: 100,
        fbaStorageFee: 60,
        netProfit: 640,
        netMargin: 0.2
      }
    ]
  }

  it('汇总字段应映射为展示字符串', () => {
    const { summary } = mapProfitReport(raw)
    expect(summary.totalRevenue).toBe('$12,456.80')
    expect(summary.totalCost).toBe('$7,234.20')
    expect(summary.grossProfit).toBe('$5,222.60')
    expect(summary.grossMargin).toBe('41.9%')
  })

  it('行数据应映射费用明细与百分比毛利率', () => {
    const { rows } = mapProfitReport(raw)
    expect(rows.length).toBe(1)
    expect(rows[0].name).toBe('2026-06-01')
    expect(rows[0].revenue).toBe('$3,200.00')
    expect(rows[0].platformFee).toBe('$480.00')
    expect(rows[0].adFee).toBe('$320.00')
    expect(rows[0].shipping).toBe('$160.00')
    expect(rows[0].profit).toBe(640)
    expect(rows[0].margin).toBe(20)
  })

  it('空 reports 应映射为空行（调用方保留降级 mock）', () => {
    const { rows, summary } = mapProfitReport({ totalRevenue: 0, reports: [] })
    expect(rows).toEqual([])
    expect(summary.grossProfit).toBe('$0.00')
  })
})

describe('mapHealthLevel（后端 healthStatus → 前端四态）', () => {
  it('五种后端状态应正确映射', () => {
    expect(mapHealthLevel('URGENT')).toEqual({ level: 'urgent', levelText: '紧急' })
    expect(mapHealthLevel('AT_RISK')).toEqual({ level: 'risk', levelText: '风险' })
    expect(mapHealthLevel('HEALTHY')).toEqual({ level: 'healthy', levelText: '健康' })
    expect(mapHealthLevel('OVERSTOCK')).toEqual({ level: 'overstock', levelText: '滞销' })
    expect(mapHealthLevel('STOCKOUT')).toEqual({ level: 'urgent', levelText: '断货' })
  })

  it('未知状态应兜底为风险/未知', () => {
    expect(mapHealthLevel(undefined)).toEqual({ level: 'risk', levelText: '未知' })
    expect(mapHealthLevel('WHATEVER')).toEqual({ level: 'risk', levelText: '未知' })
  })
})

describe('mapInventoryRow + joinSuggestQty + deriveHealthCounts', () => {
  const rows = [
    { sku: 'A', asin: 'ASIN-A', availableQuantity: 32, avg7Days: 8, daysOfSupply: 4, healthStatus: 'URGENT' },
    { sku: 'B', asin: 'ASIN-B', availableQuantity: 200, avg30Days: 2, daysOfSupply: 100, healthStatus: 'OVERSTOCK' }
  ]

  it('行映射应换算数值并带店铺名', () => {
    const item = mapInventoryRow(rows[0], 'Shop A (US)')
    expect(item).toMatchObject({
      sku: 'A',
      asin: 'ASIN-A',
      shop: 'Shop A (US)',
      stock: 32,
      dailySales: 8,
      days: 4,
      level: 'urgent',
      levelText: '紧急',
      suggestQty: 0
    })
    // avg7Days 缺失时回退 avg30Days
    expect(mapInventoryRow(rows[1], 'S').dailySales).toBe(2)
  })

  it('补货建议应按 SKU join（同 SKU 取 statDate 最新）', () => {
    const items = rows.map((r) => mapInventoryRow(r, 'S'))
    const joined = joinSuggestQty(items, [
      { sku: 'A', statDate: '2026-01-01', suggestedReplenishQty: 50 },
      { sku: 'A', statDate: '2026-02-01', suggestedReplenishQty: 120 },
      { sku: 'B' }
    ])
    expect(joined[0].suggestQty).toBe(120)
    expect(joined[1].suggestQty).toBe(0)
  })

  it('计数应与行派生口径一致', () => {
    const items = rows.map((r) => mapInventoryRow(r, 'S'))
    expect(deriveHealthCounts(items)).toEqual({ urgent: 1, risk: 0, healthy: 0, overstock: 1 })
    expect(deriveHealthCounts([])).toEqual({ urgent: 0, risk: 0, healthy: 0, overstock: 0 })
  })
})

describe('mapOrderStatus（后端 Amazon 原始状态 → 前端中文状态）', () => {
  it('四种后端状态应正确映射', () => {
    expect(mapOrderStatus('Shipped')).toEqual({ status: '已发货', statusClass: 'shipped' })
    expect(mapOrderStatus('Pending')).toEqual({ status: '待处理', statusClass: 'pending' })
    expect(mapOrderStatus('Unshipped')).toEqual({ status: '待发货', statusClass: 'pending' })
    expect(mapOrderStatus('Canceled')).toEqual({ status: '已取消', statusClass: 'refunded' })
  })

  it('未知状态应兜底', () => {
    expect(mapOrderStatus(undefined)).toEqual({ status: '未知', statusClass: 'pending' })
    expect(mapOrderStatus('WHATEVER')).toEqual({ status: '未知', statusClass: 'pending' })
  })
})

describe('mapOrderRow（后端 Order 实体 → OrderItem 展示行）', () => {
  it('实体字段应映射为展示字段', () => {
    const item = mapOrderRow(
      {
        id: 7,
        shopId: 1,
        quantity: 2,
        finalPrice: 59.98,
        amazonOrderId: '114-1234567-1234567',
        orderStatus: 'Shipped',
        purchaseDate: '2026-07-06T14:30:00'
      },
      'Shop A (US)'
    )
    expect(item).toMatchObject({
      id: 7,
      orderNo: '114-1234567-1234567',
      shop: 'Shop A (US)',
      shopId: '1',
      sku: '-',
      qty: 2,
      amount: '$59.98',
      profit: 0,
      status: '已发货',
      statusClass: 'shipped',
      date: '2026-07-06 14:30'
    })
  })

  it('缺失字段应兜底为空占位', () => {
    const item = mapOrderRow({}, 'S')
    expect(item).toMatchObject({ orderNo: '-', sku: '-', qty: 0, amount: '$0.00', date: '' })
  })
})
