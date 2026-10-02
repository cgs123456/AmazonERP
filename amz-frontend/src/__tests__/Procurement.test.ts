import { describe, it, expect, beforeEach, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { mount, flushPromises } from '@vue/test-utils'
import Procurement from '../views/Procurement.vue'

vi.mock('@/api/procurement', async () => {
  const actual = await vi.importActual<typeof import('@/api/procurement')>('@/api/procurement')
  return {
    ...actual,
    listSuppliers: vi.fn(),
    listPlans: vi.fn(),
    listOrders: vi.fn(),
    listShipments: vi.fn(),
    listShipmentItems: vi.fn(),
    suppliersBySku: vi.fn(),
    compareSuppliers: vi.fn(),
    supplierKpi: vi.fn(),
    updateSupplierStatus: vi.fn(),
    submitOrderTo1688: vi.fn(),
    cancelOrder: vi.fn(),
    receiveShipment: vi.fn(),
    createPlan: vi.fn(),
    approvePlan: vi.fn(),
    listPlanApprovals: vi.fn()
  }
})

import {
  listSuppliers, listPlans, listOrders, listShipments, listShipmentItems,
  suppliersBySku, compareSuppliers, updateSupplierStatus,
  submitOrderTo1688, cancelOrder, receiveShipment, createPlan, approvePlan, listPlanApprovals
} from '@/api/procurement'

const paged = <T,>(data: T[], truncated = false) =>
  ({
    code: 200, message: '操作成功', data, _hiddenFields: null,
    _page: { size: 50, returned: data.length, hasMore: truncated, truncated, nextCursor: truncated ? 'v1:100' : null, total: null }
  }) as any

const plain = <T,>(data: T) => ({ code: 200, message: '操作成功', data, _hiddenFields: null }) as any

// 字段名对齐 procurement 实体与状态机：Supplier.supplierName/onTimeDeliveryRate、
// PurchasePlan.planNo/suggestedQty/plannedQty、PurchaseOrder.orderNo/alibabaOrderNo、
// FbaShipment.shipmentNo/destinationFbaCode、FbaShipmentItem.receivedQuantity/freightAllocation。
const SUPPLIERS = paged([
  { id: 1, shopId: 900000000000001000, supplierName: '深圳华强北电子', supplierCode: 'SUP-001',
    contactName: '张经理', contactPhone: '13800138001', rating: 4.5, onTimeDeliveryRate: 95.5,
    qualityPassRate: 97.0, totalOrders: 15, totalAmount: 125000.0, status: 'ACTIVE' },
  { id: 2, shopId: 900000000000001000, supplierName: '义乌小商品供应链', supplierCode: 'SUP-002',
    contactName: '李小姐', rating: 3.8, onTimeDeliveryRate: 88.0, qualityPassRate: 92.5,
    totalOrders: 4, totalAmount: 21000.0, status: 'BLACKLISTED' }
])

const PLANS = paged([
  { id: 11, planNo: 'PLAN-0011', shopId: 900000000000001000, sku: 'SKU-001', suggestedQty: 200,
    plannedQty: 300, unitPrice: 15.5, totalAmount: 4650.0, urgency: 'URGENT', source: 'MANUAL', status: 'DRAFT' },
  { id: 12, planNo: 'PLAN-0012', shopId: 900000000000001000, sku: 'SKU-002', plannedQty: 100,
    unitPrice: 22.0, totalAmount: 2200.0, urgency: 'NORMAL', source: 'AUTO', status: 'PENDING_APPROVAL' },
  { id: 13, planNo: 'PLAN-0013', shopId: 900000000000001000, sku: 'SKU-003', plannedQty: 50, status: 'APPROVED' },
  // 由库存补货建议生成的草稿：依据快照留在 replenishmentData 里
  { id: 14, planNo: 'PLAN-0014', shopId: 900000000000001000, sku: 'SKU-004', suggestedQty: 90,
    plannedQty: 90, status: 'DRAFT', source: 'AUTO',
    replenishmentData: '{"basis":"inventory-replenishment-suggestion","suggestedReplenishQty":90,"daysOfSupply":4,"suggestStatDate":"2026-09-30"}' },
  { id: 15, planNo: 'PLAN-0015', shopId: 900000000000001000, sku: 'SKU-005', plannedQty: 10,
    status: 'DRAFT', replenishmentData: '人工随手填的依据，不是 JSON' }
])

const ORDERS = paged([
  { id: 21, orderNo: 'PO-0021', shopId: 900000000000001000, sku: 'SKU-001', supplierName: '深圳华强北电子',
    quantity: 300, unitPrice: 15.5, totalAmount: 4650.0, status: 'DRAFT' },
  { id: 22, orderNo: 'PO-0022', shopId: 900000000000001000, sku: 'SKU-002', quantity: 100,
    unitPrice: 22.0, totalAmount: 2200.0, status: 'QC_PENDING', alibabaOrderNo: 'AL123' },
  { id: 23, orderNo: 'PO-0023', shopId: 900000000000001000, sku: 'SKU-003', quantity: 50,
    unitPrice: 9.0, totalAmount: 450.0, status: 'QC_PASSED', alibabaOrderNo: 'AL124', trackingNo: 'SF999' }
])

const SHIPMENTS = paged([
  { id: 31, shopId: 900000000000001000, shipmentNo: 'FBA-SHIP-0031', fbaShipmentId: 'FBA18ABC',
    destinationFbaCode: 'MDW2', boxCount: 6, totalCost: 1820.5, status: 'CREATED', eta: '2026-10-30' },
  { id: 32, shopId: 900000000000001000, shipmentNo: 'FBA-SHIP-0032', destinationFbaCode: 'ONT8',
    carrier: 'UPS', masterTrackingNo: '1Z999', status: 'SHIPPED' }
])

const ITEMS = plain([
  { id: 41, fbaShipmentId: 31, sku: 'SKU-001', asin: 'B0TEST001', quantity: 100, receivedQuantity: 0,
    unitCost: 15.5, freightAllocation: 300.25, totalCost: 1850.25 },
  { id: 42, fbaShipmentId: 31, sku: 'SKU-002', asin: 'B0TEST002', quantity: 50, receivedQuantity: 50,
    unitCost: 22.0, totalCost: 1100.0 }
])

const globalStubs = {
  stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' } }
}

const withShop = () => localStorage.setItem('current_shop_id', '900000000000001000')

const happyPath = () => {
  vi.mocked(listSuppliers).mockResolvedValue(SUPPLIERS)
  vi.mocked(listPlans).mockResolvedValue(PLANS)
  vi.mocked(listOrders).mockResolvedValue(ORDERS)
  vi.mocked(listShipments).mockResolvedValue(SHIPMENTS)
  vi.mocked(listShipmentItems).mockResolvedValue(ITEMS)
}

const mountPage = async () => {
  const wrapper = mount(Procurement, { shallow: true, global: globalStubs })
  await flushPromises()
  return wrapper
}

const openTab = async (wrapper: any, label: string) => {
  const btn = wrapper.findAll('.tab').find((t: any) => t.text().includes(label))
  await btn!.trigger('click')
  await flushPromises()
}

const clickText = async (wrapper: any, label: string) => {
  const btn = wrapper.findAll('button').find((b: any) => b.text() === label)
  expect(btn, `按钮不存在：${label}`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

describe('Procurement 视图（采购供应链）', () => {
  beforeEach(() => {
    localStorage.clear()
    withShop()
    vi.clearAllMocks()
  })

  it('渲染标题与 5 个业务分区', async () => {
    happyPath()
    const wrapper = await mountPage()
    expect(wrapper.find('.hero-title').text()).toBe('采购供应链')
    expect(wrapper.findAll('.tab').length).toBe(5)
  })

  it('挂载即按当前店铺拉列表，字段直接来自后端不做页面常量', async () => {
    happyPath()
    const wrapper = await mountPage()
    expect(listSuppliers).toHaveBeenCalledWith('900000000000001000', expect.anything())
    expect(wrapper.text()).toContain('深圳华强北电子')
    expect(wrapper.text()).toContain('SUP-001')
    expect(wrapper.text()).toContain('95.5')
  })

  it('后端标记截断时提示未翻完并给出下一页入口，不把首页当全量', async () => {
    vi.mocked(listSuppliers).mockResolvedValue(paged(SUPPLIERS.data, true))
    vi.mocked(listPlans).mockResolvedValue(paged(PLANS.data, true))
    vi.mocked(listOrders).mockResolvedValue(paged([]))
    vi.mocked(listShipments).mockResolvedValue(paged([]))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('后端标记仍有下一页')
    expect(wrapper.text()).toContain('当前存在未翻完的列表')
    expect(wrapper.findAll('.page-btn').length).toBeGreaterThan(0)
  })

  it('接口失败走错误态：显示后端 message，不渲染任何兜底行', async () => {
    vi.mocked(listSuppliers).mockResolvedValue({ code: 500, message: '数据库连接失败', data: null } as any)
    vi.mocked(listPlans).mockResolvedValue(paged([]))
    vi.mocked(listOrders).mockResolvedValue(paged([]))
    vi.mocked(listShipments).mockResolvedValue(paged([]))
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('数据库连接失败')
    expect(wrapper.text()).toContain('该店铺暂无供应商记录')
  })

  it('计划状态机决定可用操作：DRAFT 只能提交审批，APPROVED 才能转采购单', async () => {
    happyPath()
    const wrapper = await mountPage()
    await openTab(wrapper, '采购计划')
    const rows = wrapper.findAll('tbody tr')
    expect(rows[0].text()).toContain('提交审批')
    expect(rows[0].text()).not.toContain('转采购单')
    expect(rows[1].text()).toContain('通过')
    expect(rows[1].text()).toContain('驳回')
    expect(rows[2].text()).toContain('转采购单')
    // 审批人未填时通过/驳回不可点，避免把空 operator 打到后端
    const approveBtn = rows[1].findAll('button').find((b: any) => b.text() === '通过')
    expect(approveBtn!.attributes('disabled')).toBeDefined()
  })

  it('审批意见随请求送出，成功后收起留痕并清空意见', async () => {
    happyPath()
    vi.mocked(approvePlan).mockResolvedValue(plain({ id: 12, status: 'APPROVED' }))
    const wrapper = await mountPage()
    await openTab(wrapper, '采购计划')
    const row = wrapper.findAll('tbody tr')[1]
    const inputs = row.findAll('input')
    expect(inputs.length).toBe(2)
    await inputs[0].setValue('张经理')
    await inputs[1].setValue('价格已核')
    await row.findAll('button').find((b: any) => b.text() === '通过')!.trigger('click')
    await flushPromises()
    expect(approvePlan).toHaveBeenCalledWith(12, '张经理', true, '价格已核')
    // 审批改了状态，留痕面板展示的是刚被这次操作改过的数据，必须收起
    expect(wrapper.text()).not.toContain('审批留痕（PLAN-0012）')
  })

  it('审批留痕按计划 id 拉取，动作/操作人/意见/时间都来自后端', async () => {
    happyPath()
    vi.mocked(listPlanApprovals).mockResolvedValue(plain([
      { id: 2, shopId: 900000000000001000, refType: 'PLAN', refId: 12, action: 'APPROVE',
        operator: '张经理', comment: '价格已核', createTime: '2026-10-03T10:00:00' },
      { id: 1, shopId: 900000000000001000, refType: 'PLAN', refId: 12, action: 'REJECT',
        operator: '李四', comment: null, createTime: '2026-10-02T09:00:00' }
    ]))
    const wrapper = await mountPage()
    await openTab(wrapper, '采购计划')
    const row = wrapper.findAll('tbody tr')[1]
    await row.findAll('button').find((b: any) => b.text() === '审批留痕')!.trigger('click')
    await flushPromises()
    expect(listPlanApprovals).toHaveBeenCalledWith(12)
    const text = wrapper.text()
    expect(text).toContain('审批留痕（PLAN-0012）')
    expect(text).toContain('APPROVE')
    expect(text).toContain('张经理')
    expect(text).toContain('价格已核')
    expect(text).toContain('（无意见）')
    expect(text).toContain('2026-10-02T09:00:00')
  })

  it('没有留痕的计划说明留痕才开始写，不伪造历史行；DRAFT 计划不给留痕入口', async () => {
    happyPath()
    vi.mocked(listPlanApprovals).mockResolvedValue(plain([]))
    const wrapper = await mountPage()
    await openTab(wrapper, '采购计划')
    expect(wrapper.findAll('tbody tr')[0].text()).not.toContain('审批留痕')
    const row = wrapper.findAll('tbody tr')[1]
    await row.findAll('button').find((b: any) => b.text() === '审批留痕')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('不会伪造')
    expect(wrapper.text()).toContain('2026-10-03')
    expect(wrapper.findAll('.trail-row tbody tr').length).toBe(0)

    // 异常响应（data 不是数组）也不能被补成一行。
    // 展开留痕后计划行下面多出一行 DOM，所以只能按 id 找回同一行：
    // 再点一次是「收起」，第三次才是带新响应重新拉取。
    const rowOfPlan12 = () => wrapper.findAll('tbody tr')[1]
    vi.mocked(listPlanApprovals).mockResolvedValue(plain(null))
    await rowOfPlan12().findAll('button').find((b: any) => b.text() === '审批留痕')!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.trail-row').length).toBe(0)
    await rowOfPlan12().findAll('button').find((b: any) => b.text() === '审批留痕')!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.trail-row').length).toBe(1)
    expect(wrapper.findAll('.trail-row tbody tr').length).toBe(0)
    expect(wrapper.findAll('.trail-row tbody tr').length).toBe(0)
    expect(wrapper.text()).toContain('不会伪造')
  })

  it('提交 1688 是真实下单，必须经确认弹窗才发出请求', async () => {
    happyPath()
    vi.mocked(submitOrderTo1688).mockResolvedValue(plain({ id: 21, status: 'SUBMITTED' }))
    const wrapper = await mountPage()
    await openTab(wrapper, '采购单')
    await clickText(wrapper, '提交 1688')
    expect(submitOrderTo1688).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('在 1688 生成真实采购订单')
    await clickText(wrapper, '确认执行')
    expect(submitOrderTo1688).toHaveBeenCalledWith(21)
  })

  it('远程关单失败时后端返回 false，页面必须说明本地状态未变', async () => {
    happyPath()
    vi.mocked(cancelOrder).mockResolvedValue(plain(false))
    const wrapper = await mountPage()
    await openTab(wrapper, '采购单')
    const cancelBtn = wrapper.findAll('button').filter((b: any) => b.text() === '取消')[0]
    await cancelBtn.trigger('click')
    await flushPromises()
    await clickText(wrapper, '确认执行')
    expect(cancelOrder).toHaveBeenCalledWith(21)
    expect(wrapper.find('.toast').text()).toContain('1688 远程关单失败，本地状态保持不变')
  })

  it('QC_PASSED/CANCELED 终态不再显示取消按钮', async () => {
    happyPath()
    vi.mocked(listOrders).mockResolvedValue(paged([
      { id: 1, orderNo: 'PO-1', sku: 'A', quantity: 1, unitPrice: 1, status: 'QC_PASSED' },
      { id: 2, orderNo: 'PO-2', sku: 'B', quantity: 1, unitPrice: 1, status: 'CANCELED' }
    ]))
    const wrapper = await mountPage()
    await openTab(wrapper, '采购单')
    expect(wrapper.findAll('button').filter((b: any) => b.text() === '取消').length).toBe(0)
  })

  it('签收默认补满未收行、排除已收行，并把差异如实显示', async () => {
    happyPath()
    vi.mocked(receiveShipment).mockResolvedValue(plain({
      shipmentId: 31, shipmentNo: 'FBA-SHIP-0031', hasDiscrepancy: true, allReceived: false,
      pendingItemCount: 1,
      itemResults: [{ sku: 'SKU-001', expectedQty: 100, receivedQty: 98, discrepancy: -2, status: 'SHORT_RECEIVING' }]
    }))
    const wrapper = await mountPage()
    await openTab(wrapper, 'FBA 货件')
    await clickText(wrapper, '明细')
    expect(listShipmentItems).toHaveBeenCalledWith(31)
    expect(wrapper.text()).toContain('SKU-002')
    await clickText(wrapper, '提交签收')
    expect(receiveShipment).toHaveBeenCalledWith(31, [{ itemId: 41, receivedQty: 100 }])
    expect(wrapper.text()).toContain('存在签收差异')
    expect(wrapper.text()).toContain('SHORT_RECEIVING')
  })

  it('SKU 无供货关系时说明是真实空态，比价表不编造行', async () => {
    happyPath()
    vi.mocked(suppliersBySku).mockResolvedValue(plain([]))
    vi.mocked(compareSuppliers).mockResolvedValue(plain([]))
    const wrapper = await mountPage()
    const input = wrapper.findAll('input').find((i: any) => (i.attributes('placeholder') || '').includes('输入 SKU'))
    await input!.setValue('SKU-NOPE')
    await clickText(wrapper, '查询')
    expect(wrapper.text()).toContain('该 SKU 没有供货关系')
    expect(wrapper.findAll('.compare-table').length).toBe(0)
  })

  it('拉黑供应商要确认，确认后按状态码调用后端', async () => {
    happyPath()
    vi.mocked(updateSupplierStatus).mockResolvedValue(plain(true))
    const wrapper = await mountPage()
    await clickText(wrapper, '拉黑')
    expect(updateSupplierStatus).not.toHaveBeenCalled()
    await clickText(wrapper, '确认执行')
    expect(updateSupplierStatus).toHaveBeenCalledWith(1, 'BLACKLISTED')
  })

  it('新建计划带上当前店铺并标记为手工来源', async () => {
    happyPath()
    vi.mocked(createPlan).mockResolvedValue(plain({ id: 99, planNo: 'PLAN-0099', status: 'DRAFT' }))
    const wrapper = await mountPage()
    await openTab(wrapper, '采购计划')
    await clickText(wrapper, '新建计划')
    const inputs = wrapper.findAll('.form-grid input')
    await inputs[0].setValue('SKU-NEW')
    await inputs[1].setValue('88')
    await clickText(wrapper, '提交')
    expect(createPlan).toHaveBeenCalledWith(expect.objectContaining({
      // type=number 输入框由 Vue 转成数字，不是字符串
      sku: 'SKU-NEW', plannedQty: 88, shopId: '900000000000001000', source: 'MANUAL'
    }))
  })

  it('造数端点 promotion/plan 留在注释里说明，不出现在任何请求中', () => {
    const src = readFileSync('src/api/procurement.ts', 'utf8')
    expect(src).toMatch(/promotion\/plan/)
    expect(src).not.toMatch(/request\.(get|post|put)[^;]*promotion/)
  })

  it('补货依据留档可读：JSON 快照展开成字段，非 JSON 原样显示不丢弃', async () => {
    happyPath()
    const wrapper = await mountPage()
    await openTab(wrapper, '采购计划')
    const basisBtns = wrapper.findAll('button').filter((b: any) => b.text() === '补货依据')
    expect(basisBtns.length).toBe(2)
    await basisBtns[0].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('PLAN-0014 的补货依据')
    expect(wrapper.text()).toContain('suggestedReplenishQty')
    expect(wrapper.text()).toContain('2026-09-30')
    expect(wrapper.text()).toContain('不是当前库存的实时值')

    // 关掉面板后再看第二条：依据不是 JSON 时必须原样显示，不能被吞掉
    await wrapper.find('.panel .action-btn').trigger('click')
    const again = wrapper.findAll('button').filter((b: any) => b.text() === '补货依据')
    await again[1].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('人工随手填的依据，不是 JSON')
    expect(wrapper.text()).toContain('原样显示')
  })
})
