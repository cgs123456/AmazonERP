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
    listVoucherSourceOrders: vi.fn(),
    listShipments: vi.fn(),
    listShipmentItems: vi.fn(),
    getShipment: vi.fn(),
    createShipment: vi.fn(),
    updateShipment: vi.fn(),
    createSupplier: vi.fn(),
    getSupplier: vi.fn(),
    updateSupplier: vi.fn(),
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
  listSuppliers, listPlans, listOrders, listVoucherSourceOrders, listShipments, listShipmentItems,
  getShipment, createShipment, updateShipment,
  createSupplier, getSupplier, updateSupplier,
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
  // paymentTerms 是刻意放的陈旧哨兵值：编辑回填必须取 getSupplier 单条，
  // 复用列表行就会把它带回表单（列表可能已被他人改过）
  { id: 1, shopId: 900000000000001000, supplierName: '深圳华强北电子', supplierCode: 'SUP-001',
    contactName: '张经理', contactPhone: '13800138001', rating: 4.5, onTimeDeliveryRate: 95.5,
    qualityPassRate: 97.0, totalOrders: 15, totalAmount: 125000.0, paymentTerms: '月结30天', status: 'ACTIVE' },
  { id: 2, shopId: 900000000000001000, supplierName: '义乌小商品供应链', supplierCode: 'SUP-002',
    contactName: '李小姐', rating: 3.8, onTimeDeliveryRate: 88.0, qualityPassRate: 92.5,
    totalOrders: 4, totalAmount: 21000.0, status: 'BLACKLISTED' }
])

// getSupplier 单条：含列表表格不展示的主数据（邮箱/地址/1688 店页/会员号/备注/累计金额）。
// rating/onTimeDeliveryRate/qualityPassRate/totalOrders/totalAmount 由后端按订单履约自动累计
// （updateSupplierStats），编辑白名单刻意排除——表单能改它们就会把统计冲掉。
const SUPPLIER_DETAIL = plain({
  id: 1, shopId: 900000000000001000, supplierName: '深圳华强北电子', supplierCode: 'SUP-001',
  contactName: '张经理', contactPhone: '13800138000', contactEmail: 'zhang@hqb-electronics.cn',
  address: '广东省深圳市福田区华强北街道1001号', alibabaShopUrl: 'https://shop1688.example/hqb',
  alibabaMemberId: 'ali-member-7788', paymentTerms: '月结60天', rating: 4.5,
  onTimeDeliveryRate: 95.5, qualityPassRate: 97.0, priceCompetitiveness: null, responseSpeed: null,
  totalOrders: 15, totalAmount: 125000.0, status: 'ACTIVE', remark: '优先走顺丰到付'
})

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
  // totalWeight/eta 是刻意放的陈旧哨兵值：详情/编辑回填必须取 getShipment 单条，
  // 复用列表行就会把这些值带进表单（列表可能已被他人改过）
  { id: 31, shopId: 900000000000001000, shipmentNo: 'FBA-SHIP-0031', fbaShipmentId: 'FBA18ABC',
    destinationFbaCode: 'MDW2', boxCount: 6, totalWeight: 999, totalCost: 1820.5, eta: '2026-10-01',
    status: 'CREATED' },
  { id: 32, shopId: 900000000000001000, shipmentNo: 'FBA-SHIP-0032', destinationFbaCode: 'ONT8',
    carrier: 'UPS', masterTrackingNo: '1Z999', status: 'SHIPPED' }
])

// getShipment 单条：含列表表格不展示的主数据字段（体积重量/成本构成/实际到货日/仓库）
const SHIPMENT_DETAIL = plain({
  id: 31, shopId: 900000000000001000, shipmentNo: 'FBA-SHIP-0031', fbaShipmentId: 'FBA18ABC',
  warehouseId: 3, destinationFbaCode: 'MDW2', destinationAddress: '上海市浦东新区美盛路168号',
  shippingMethod: 'SEA', carrier: '中外运', masterTrackingNo: 'COSU1234567',
  boxCount: 6, totalWeight: 1500.5, totalVolume: 12.5,
  freightCost: 800.0, customsCost: 200.0, taxCost: 50.5, otherCost: 20.0, totalCost: 1071.0,
  status: 'CREATED', eta: '2026-11-15', actualArrival: null
})

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
  // 凭证来源默认与全量列表同形，具体用例再覆盖成「只有可入账三态」
  vi.mocked(listVoucherSourceOrders).mockResolvedValue(ORDERS)
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

  it('渲染标题与业务分区', async () => {
    happyPath()
    const wrapper = await mountPage()
    expect(wrapper.find('.hero-title').text()).toBe('采购供应链')
    // 5 个业务分区 + 凭证来源 + 供应商付款 + 采购发票 + BOM 组合品
    expect(wrapper.findAll('.tab').length).toBe(9)
    const labels = wrapper.findAll('.tab').map((t: any) => t.text()).join('|')
    expect(labels).toContain('凭证来源')
    expect(labels).toContain('供应商付款')
    expect(labels).toContain('采购发票')
    expect(labels).toContain('BOM 组合品')
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

describe('Procurement FBA 货件详情/编辑（getShipment + updateShipment）', () => {
  beforeEach(() => {
    localStorage.clear()
    withShop()
    vi.clearAllMocks()
  })

  const openShipmentTab = async () => {
    happyPath()
    const wrapper = await mountPage()
    await openTab(wrapper, 'FBA 货件')
    return wrapper
  }

  const rowButton = (wrapper: any, label: string) =>
    wrapper.find('[data-panel="shipment"]').findAll('button').find((b: any) => b.text() === label)

  it('详情按钮拉单条，展示列表表格不展示的主数据字段（体积/重量/成本构成/实际到货日）', async () => {
    const wrapper = await openShipmentTab()
    vi.mocked(getShipment).mockResolvedValue(SHIPMENT_DETAIL)
    await rowButton(wrapper, '详情')!.trigger('click')
    await flushPromises()
    expect(getShipment).toHaveBeenCalledWith(31)
    const detail = wrapper.find('[data-panel="shipment-detail"]')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).toContain('FBA-SHIP-0031')
    expect(detail.text()).toContain('上海市浦东新区美盛路168号')
    expect(detail.text()).toContain('1500.5')
    expect(detail.text()).toContain('12.5')
    expect(detail.text()).toContain('800')
    expect(detail.text()).toContain('中外运')
  })

  it('详情失败按标签前缀点名原因，不铺空详情面板', async () => {
    const wrapper = await openShipmentTab()
    vi.mocked(getShipment).mockResolvedValue({ code: 403, message: '无权操作该店铺的货件', data: null } as any)
    await rowButton(wrapper, '详情')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-panel="shipment-detail"]').exists()).toBe(false)
    expect(wrapper.find('.error-zone').text()).toContain('货件详情：无权操作该店铺的货件')
  })

  it('编辑入口先拉单条再回填：表单值来自单条而非列表行', async () => {
    const wrapper = await openShipmentTab()
    vi.mocked(getShipment).mockResolvedValue(SHIPMENT_DETAIL)
    await rowButton(wrapper, '编辑')!.trigger('click')
    await flushPromises()
    expect(getShipment).toHaveBeenCalledWith(31)
    expect(wrapper.text()).toContain('编辑 FBA 货件')
    // input 顺序：FBA ShipmentId / 目的仓代码 / 箱数 / 总重 / 总体积 / 运费 / 关税 / 税费 / 其他费用 / ETA
    const inputs = wrapper.findAll('.form-grid input')
    expect((inputs[3].element as HTMLInputElement).value).toBe('1500.5')
    expect((inputs[3].element as HTMLInputElement).value).not.toBe('999')
    expect((inputs[9].element as HTMLInputElement).value).toBe('2026-11-15')
  })

  it('编辑拉取被拒时不开弹窗：不拿列表陈旧值回填出一份假表单', async () => {
    const wrapper = await openShipmentTab()
    vi.mocked(getShipment).mockResolvedValue({ code: 404, message: '货件不存在', data: null } as any)
    await rowButton(wrapper, '编辑')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(false)
    expect(wrapper.find('.error-zone').text()).toContain('货件详情：货件不存在')
  })

  it('编辑提交走 PUT：body 只送可编辑字段，不带 status/shipmentNo/shopId', async () => {
    const wrapper = await openShipmentTab()
    vi.mocked(getShipment).mockResolvedValue(SHIPMENT_DETAIL)
    vi.mocked(updateShipment).mockResolvedValue(SHIPMENT_DETAIL)
    await rowButton(wrapper, '编辑')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('.form-grid input')[2].setValue('8')
    await clickText(wrapper, '提交')
    await flushPromises()
    expect(updateShipment).toHaveBeenCalledTimes(1)
    expect(updateShipment).toHaveBeenCalledWith(31, expect.objectContaining({ boxCount: 8 }))
    const body = vi.mocked(updateShipment).mock.calls[0][1] as Record<string, unknown>
    // 状态推进有专用端点（确认发货/标记到货），主数据编辑不能绕过它们的副作用
    expect(body).not.toHaveProperty('status')
    // shipmentNo 创建时由后端生成、shopId 以既有记录为准（防把货件搬到别的店铺）
    expect(body).not.toHaveProperty('shipmentNo')
    expect(body).not.toHaveProperty('shopId')
    expect(body).not.toHaveProperty('actualArrival')
    expect(createShipment).not.toHaveBeenCalled()
    // 成功后刷新列表（挂载 1 次 + 编辑后 1 次）
    expect(listShipments).toHaveBeenCalledTimes(2)
  })

  it('编辑弹窗不给状态下拉：状态只能走「确认发货/标记到货」，并披露重跑分摊', async () => {
    const wrapper = await openShipmentTab()
    vi.mocked(getShipment).mockResolvedValue(SHIPMENT_DETAIL)
    await rowButton(wrapper, '编辑')!.trigger('click')
    await flushPromises()
    const modal = wrapper.find('.modal')
    // 只剩「发货方式」一个下拉：状态推进必须走专用端点
    expect(modal.findAll('select').length).toBe(1)
    expect(modal.text()).toContain('状态')
    expect(wrapper.text()).toContain('确认发货')
    // 成本改动不会自动重算明细单位成本，必须说出这一点
    expect(wrapper.text()).toContain('费用分摊')
  })

  it('新建货件仍保留初始状态下拉，创建走后端默认值', async () => {
    const wrapper = await openShipmentTab()
    vi.mocked(createShipment).mockResolvedValue(plain({ id: 99, shipmentNo: 'FBA-SHIP-0099', status: 'CREATED' }))
    await clickText(wrapper, '新建货件')
    expect(wrapper.find('.modal').findAll('select').length).toBe(2)
    await clickText(wrapper, '提交')
    await flushPromises()
    expect(createShipment).toHaveBeenCalledWith(expect.objectContaining({
      shopId: '900000000000001000', status: 'CREATED'
    }))
    expect(updateShipment).not.toHaveBeenCalled()
  })
})

describe('Procurement 供应商详情/编辑（getSupplier + updateSupplier）', () => {
  beforeEach(() => {
    localStorage.clear()
    withShop()
    vi.clearAllMocks()
  })

  const mountSupplierTab = async () => {
    happyPath()
    const wrapper = await mountPage()
    // 供应商 Tab 是默认 Tab，无需切换
    return wrapper
  }

  const rowButton = (wrapper: any, label: string) =>
    wrapper.findAll('tbody tr')[0].findAll('button').find((b: any) => b.text() === label)

  it('详情按钮拉单条，展示列表表格不展示的主数据（邮箱/地址/1688 店页/备注）', async () => {
    const wrapper = await mountSupplierTab()
    vi.mocked(getSupplier).mockResolvedValue(SUPPLIER_DETAIL)
    await rowButton(wrapper, '详情')!.trigger('click')
    await flushPromises()
    expect(getSupplier).toHaveBeenCalledWith(1)
    const detail = wrapper.find('[data-panel="supplier-detail"]')
    expect(detail.exists()).toBe(true)
    expect(detail.text()).toContain('zhang@hqb-electronics.cn')
    expect(detail.text()).toContain('华强北街道1001号')
    expect(detail.text()).toContain('https://shop1688.example/hqb')
    expect(detail.text()).toContain('ali-member-7788')
    expect(detail.text()).toContain('优先走顺丰到付')
  })

  it('详情失败按标签前缀点名原因，不铺空详情面板', async () => {
    const wrapper = await mountSupplierTab()
    vi.mocked(getSupplier).mockResolvedValue({ code: 403, message: '无权操作该店铺的供应商', data: null } as any)
    await rowButton(wrapper, '详情')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-panel="supplier-detail"]').exists()).toBe(false)
    expect(wrapper.find('.error-zone').text()).toContain('供应商详情：无权操作该店铺的供应商')
  })

  it('编辑入口先拉单条再回填：表单值来自单条而非列表行', async () => {
    const wrapper = await mountSupplierTab()
    vi.mocked(getSupplier).mockResolvedValue(SUPPLIER_DETAIL)
    await rowButton(wrapper, '编辑')!.trigger('click')
    await flushPromises()
    expect(getSupplier).toHaveBeenCalledWith(1)
    expect(wrapper.text()).toContain('编辑供应商')
    // input 顺序：供应商名称 / 编码 / 联系人 / 联系电话 / 邮箱 / 1688 店页 / 结算方式
    const inputs = wrapper.findAll('.form-grid input')
    expect(inputs.length).toBe(7)
    expect((inputs[6].element as HTMLInputElement).value).toBe('月结60天')
    expect((inputs[6].element as HTMLInputElement).value).not.toBe('月结30天')
  })

  it('编辑拉取被拒时不开弹窗：不拿列表陈旧值回填出一份假表单', async () => {
    const wrapper = await mountSupplierTab()
    vi.mocked(getSupplier).mockResolvedValue({ code: 404, message: '供应商不存在', data: null } as any)
    await rowButton(wrapper, '编辑')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(false)
    expect(wrapper.find('.error-zone').text()).toContain('供应商详情：供应商不存在')
  })

  it('编辑提交走 PUT：body 不碰评分/状态/店铺等系统管辖字段', async () => {
    const wrapper = await mountSupplierTab()
    vi.mocked(getSupplier).mockResolvedValue(SUPPLIER_DETAIL)
    vi.mocked(updateSupplier).mockResolvedValue(SUPPLIER_DETAIL)
    await rowButton(wrapper, '编辑')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('.form-grid input')[0].setValue('深圳华强北电子（新名）')
    await clickText(wrapper, '提交')
    await flushPromises()
    expect(updateSupplier).toHaveBeenCalledTimes(1)
    expect(updateSupplier).toHaveBeenCalledWith(1, expect.objectContaining({
      supplierName: '深圳华强北电子（新名）'
    }))
    const body = vi.mocked(updateSupplier).mock.calls[0][1] as Record<string, unknown>
    // 评分与三项统计由订单履约自动累计（updateSupplierStats），表单能改就会把统计冲掉
    expect(body).not.toHaveProperty('rating')
    expect(body).not.toHaveProperty('onTimeDeliveryRate')
    expect(body).not.toHaveProperty('qualityPassRate')
    expect(body).not.toHaveProperty('totalOrders')
    expect(body).not.toHaveProperty('totalAmount')
    // 状态走停用/启用/拉黑专用按钮，店铺归属后端按既有记录定
    expect(body).not.toHaveProperty('status')
    expect(body).not.toHaveProperty('shopId')
    expect(createSupplier).not.toHaveBeenCalled()
    expect(listSuppliers).toHaveBeenCalledTimes(2)
  })

  it('编辑弹窗不给评分输入：评分是自动累计的统计值，并披露编码唯一与留空语义', async () => {
    const wrapper = await mountSupplierTab()
    vi.mocked(getSupplier).mockResolvedValue(SUPPLIER_DETAIL)
    await rowButton(wrapper, '编辑')!.trigger('click')
    await flushPromises()
    const modal = wrapper.find('.modal')
    expect(modal.text()).toContain('评分')
    expect(modal.text()).toContain('唯一')
    expect(modal.text()).toContain('留空')
    // 新建表单 8 个输入（含评分），编辑 7 个——评分输入整个不出现
    expect(modal.findAll('input').length).toBe(7)
  })

  it('新建供应商仍保留评分输入（初始值），不受编辑白名单影响', async () => {
    const wrapper = await mountSupplierTab()
    vi.mocked(createSupplier).mockResolvedValue(plain({ id: 99, supplierCode: 'SUP-0099' }))
    await clickText(wrapper, '新增供应商')
    expect(wrapper.find('.modal').findAll('input').length).toBe(8)
    await wrapper.find('.form-grid input').setValue('新供应商')
    await clickText(wrapper, '提交')
    await flushPromises()
    expect(createSupplier).toHaveBeenCalledWith(expect.objectContaining({
      shopId: '900000000000001000', rating: 4.0
    }))
    expect(updateSupplier).not.toHaveBeenCalled()
  })
})

describe('Procurement 凭证来源 Tab（GET /procurement/order/voucher-source/{shopId}）', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    withShop()
    happyPath()
  })

  it('取数打的是凭证来源端点，不是全量采购单列表', async () => {
    vi.mocked(listVoucherSourceOrders).mockResolvedValue(paged([
      { id: 77, orderNo: 'PO-V-77', sku: 'SKU-V', supplierName: '仅可入账的单', quantity: 5,
        unitPrice: 9.9, totalAmount: 49.5, status: 'COMPLETED' }
    ]))

    const wrapper = await mountPage()
    await openTab(wrapper, '凭证来源')

    expect(listVoucherSourceOrders).toHaveBeenCalled()
    const panel = wrapper.find('[data-panel="voucher"]')
    expect(panel.text()).toContain('PO-V-77')
    expect(panel.text()).toContain('仅可入账的单')
    // 口径由后端定：说明里必须点出三态与「翻完」
    expect(panel.text()).toContain('QC_PASSED')
    expect(panel.text()).toContain('翻完')
  })

  it('被截断时按游标续拉，不把半页当全部', async () => {
    vi.mocked(listVoucherSourceOrders)
      .mockResolvedValueOnce(paged([{ id: 77, orderNo: 'PO-V-77', sku: 'A', status: 'RECEIVED' }], true))
      .mockResolvedValueOnce(paged([{ id: 78, orderNo: 'PO-V-78', sku: 'B', status: 'QC_PASSED' }]))

    const wrapper = await mountPage()
    await openTab(wrapper, '凭证来源')

    const panel = wrapper.find('[data-panel="voucher"]')
    await clickText(panel, '加载下一页')

    expect(listVoucherSourceOrders).toHaveBeenCalledTimes(2)
    expect(vi.mocked(listVoucherSourceOrders).mock.calls[1][1]).toMatchObject({ cursor: 'v1:100' })
    expect(panel.text()).toContain('PO-V-78')
  })

  it('端点失败时报错而不是显示成「没有可入账的单」', async () => {
    vi.mocked(listVoucherSourceOrders).mockResolvedValue({ code: 500, message: '库没连上', data: [] } as any)

    const wrapper = await mountPage()
    await openTab(wrapper, '凭证来源')

    expect(wrapper.find('.error-zone').text()).toContain('凭证来源采购单')
  })
})
