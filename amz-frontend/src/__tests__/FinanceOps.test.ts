import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Finance from '../views/Finance.vue'

vi.mock('@/api/finance', () => ({
  listVouchers: vi.fn(),
  syncToKingdee: vi.fn(),
  calculateProfit: vi.fn()
}))

vi.mock('@/api/finance-ext', async () => {
  const actual = await vi.importActual<typeof import('@/api/finance-ext')>('@/api/finance-ext')
  return {
    ...actual,
    listCollections: vi.fn(),
    collectionSummary: vi.fn(),
    rebuildCollections: vi.fn(),
    listSettlements: vi.fn(),
    syncSettlement: vi.fn(),
    listDiscrepancies: vi.fn(),
    scanDiscrepancies: vi.fn(),
    dismissDiscrepancy: vi.fn(),
    createClaimFromDiscrepancy: vi.fn(),
    intakeInboundShortage: vi.fn(),
    listClaims: vi.fn(),
    claimSummary: vi.fn(),
    reconcileClaims: vi.fn(),
    submitClaim: vi.fn(),
    acceptClaim: vi.fn(),
    reimburseClaim: vi.fn(),
    rejectClaim: vi.fn(),
    skuProfit: vi.fn(),
    calculateVat: vi.fn(),
    vatRate: vi.fn(),
    vatThreshold: vi.fn(),
    vatMonthlyClose: vi.fn(),
    generateSettlementVouchers: vi.fn(),
    generateProcurementVouchers: vi.fn()
  }
})

import { listVouchers } from '@/api/finance'

vi.mock('@/api/procurement', () => ({ listReceiptShortages: vi.fn() }))
import { listReceiptShortages } from '@/api/procurement'
import * as ext from '@/api/finance-ext'

const ok = <T,>(data: T, page?: unknown) =>
  ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: page ?? null }) as any

const pageOf = (nextCursor: string | null, truncated = nextCursor !== null) =>
  ({ size: 50, returned: 1, hasMore: truncated, truncated, nextCursor, total: null })

const mockedVouchers = vi.mocked(listVouchers)
const mockedShortages = vi.mocked(listReceiptShortages)

const SHORTAGES = ok([
  { itemId: 41, shipmentId: 31, shipmentNo: 'FBA-SHIP-0031', fbaShipmentId: 'FBA18ABC',
    sku: 'SKU-9', asin: 'B9', expectedQty: 100, receivedQty: 88, shortUnits: 12,
    unitCost: 12.5, totalCost: 150, shipmentStatus: 'RECEIVING' }
], pageOf(null))

const COLLECTIONS = ok([
  { id: 1, shopId: 900000000000001000, amazonOrderId: '114-7788-1234', currency: 'USD',
    receivable: 120.5, feeDeducted: 18.08, refunded: 0, reimbursed: 2.5, netReceived: 104.92,
    shortfall: 0, depositDate: '2026-09-30', status: 'SETTLED' },
  { id: 2, shopId: 900000000000001000, amazonOrderId: '114-7788-5678', currency: 'USD',
    receivable: 60, feeDeducted: 9, refunded: 60, reimbursed: 0, netReceived: 0,
    shortfall: 12.34, depositDate: null, status: 'SHORTFALL' }
], pageOf(null))

const COL_SUMMARY = ok({
  shopId: 900000000000001000, totalOrders: 2, pendingOrders: 0, inTransitOrders: 1,
  settledOrders: 1, refundedOrders: 1, shortfallOrders: 1,
  receivableTotal: 180.5, netReceivedTotal: 104.92, inTransitAmount: 51, settledAmount: 104.92,
  shortfallAmount: 12.34, currencies: ['USD'], warnings: ['存在未归类交易类型 Liquidations']
})

const SETTLEMENTS = ok([
  { id: 11, shopId: 900000000000001000, settlementId: 'S-1', amazonOrderId: '114-7788-1234',
    sku: 'SKU-1', transactionType: 'Order', amountType: 'Principal', amount: 120.5,
    currency: 'USD', depositDate: '2026-09-30', rowKey: 'rk-1', source: 'SP_API' },
  { id: 12, shopId: 900000000000001000, settlementId: 'S-1', amazonOrderId: '114-7788-1234',
    sku: 'SKU-1', transactionType: 'Order', amountType: 'Commission', amount: -18.08,
    currency: 'USD', depositDate: '2026-09-30', rowKey: 'rk-2', source: 'SP_API' }
], pageOf(null))

const DISCREPANCIES = ok([
  { id: 21, shopId: 900000000000001000, sku: 'SKU-9', shipmentId: 'FBA123',
    discrepancyType: 'INBOUND_SHORTAGE', expectedAmount: 100, actualAmount: 70, difference: -30,
    currency: 'USD', status: 'OPEN', detectedAt: '2026-10-01T09:00:00' }
], pageOf(null))

const CLAIMS = ok([
  { id: 31, shopId: 900000000000001000, claimNo: 'CLM-0031', sku: 'SKU-9', shipmentId: 'FBA123',
    discrepancyType: 'INBOUND_SHORTAGE', claimAmount: 30, reimbursedAmount: 0, currency: 'USD', status: 'CANDIDATE' },
  { id: 32, shopId: 900000000000001000, claimNo: 'CLM-0032', sku: 'SKU-8', claimAmount: 12,
    reimbursedAmount: 0, currency: 'USD', status: 'ACCEPTED' }
], pageOf(null))

const globalStubs = {
  stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true }
}

const openTab = async (wrapper: any, label: string) => {
  const btn = wrapper.findAll('.dim-tab').find((b: any) => b.text() === label)
  await btn!.trigger('click')
  await flushPromises()
}

const clickBtn = async (wrapper: any, label: string) => {
  const btn = wrapper.findAll('button').find((b: any) => b.text() === label)
  expect(btn, `按钮不存在：${label}`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

const byText = (wrapper: any, text: string) => wrapper.text().includes(text)

describe('Finance 视图：财务运营分区（回款/结算/差异/索赔/单品利润/VAT）', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '900000000000001000')
    vi.clearAllMocks()
    mockedVouchers.mockResolvedValue(ok([], pageOf(null)))
    mockedShortages.mockResolvedValue(SHORTAGES)
  })

  it('挂载只拉凭证，其余分区首次进入才请求（不在首屏打满 8 个重端点）', async () => {
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    expect(listVouchers).toHaveBeenCalled()
    expect(ext.listCollections).not.toHaveBeenCalled()
    await openTab(wrapper, '回款对账')
    expect(ext.listCollections).toHaveBeenCalledTimes(1)
    expect(ext.collectionSummary).toHaveBeenCalledTimes(1)
    await openTab(wrapper, '凭证列表')
    await openTab(wrapper, '回款对账')
    expect(ext.listCollections).toHaveBeenCalledTimes(1)
  })

  it('回款表按后端真实字段渲染，汇总卡显示聚合数而不是前端合计', async () => {
    vi.mocked(ext.listCollections).mockResolvedValue(COLLECTIONS)
    vi.mocked(ext.collectionSummary).mockResolvedValue(COL_SUMMARY)
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '回款对账')
    expect(byText(wrapper, '114-7788-1234')).toBe(true)
    expect(byText(wrapper, '104.92')).toBe(true)
    expect(byText(wrapper, '¥180.5')).toBe(true)
    // 后端 warnings 必须显示：它说明口径本身有未归类交易
    expect(byText(wrapper, '存在未归类交易类型')).toBe(true)
  })

  it('回款接口失败：显示后端原因，不把失败伪装成「暂无数据」的正常态', async () => {
    vi.mocked(ext.listCollections).mockResolvedValue({ code: 503, message: '财务库不可用', data: null } as any)
    vi.mocked(ext.collectionSummary).mockResolvedValue(ok(null))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '回款对账')
    expect(byText(wrapper, '财务库不可用')).toBe(true)
    const panel = wrapper.find('[data-panel="collection"]')
    expect(panel.findAll('tbody tr').length).toBe(1) // 只剩空态行
    expect(panel.text()).toContain('暂无回款记录')
  })

  it('重算回款与结算同步都会真的动数据，必须过确认弹窗', async () => {
    vi.mocked(ext.listCollections).mockResolvedValue(COLLECTIONS)
    vi.mocked(ext.collectionSummary).mockResolvedValue(COL_SUMMARY)
    vi.mocked(ext.rebuildCollections).mockResolvedValue(ok(2))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '回款对账')
    await clickBtn(wrapper, '重算回款')
    expect(ext.rebuildCollections).not.toHaveBeenCalled()
    expect(byText(wrapper, '结算原表若还没同步，重算只会得到空结果')).toBe(true)
    await clickBtn(wrapper, '确认执行')
    expect(ext.rebuildCollections).toHaveBeenCalledWith('900000000000001000')
  })

  it('结算同步报告把「读到 / 入库 / 跳过 / 失败」四个数分开显示', async () => {
    vi.mocked(ext.listSettlements).mockResolvedValue(SETTLEMENTS)
    vi.mocked(ext.syncSettlement).mockResolvedValue(ok({
      shopId: 1, dataLineCount: 320, inserted: 300, skipped: 18, failed: 2,
      sumAmount: 1000, currencies: ['USD'], rowErrors: []
    }))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '结算原表')
    await clickBtn(wrapper, '从 SP-API 同步结算')
    await clickBtn(wrapper, '确认执行')
    expect(byText(wrapper, '读到的报表行数')).toBe(true)
    expect(wrapper.text()).toContain('320')
    expect(byText(wrapper, '重复跳过')).toBe(true)
    expect(byText(wrapper, '失败行')).toBe(true)
    expect(byText(wrapper, 'Order')).toBe(true)
    // 负数扣费行按负数显示，不取绝对值粉饰
    expect(byText(wrapper, '-18.08')).toBe(true)
  })

  it('费用差异：只有 OPEN 能生成索赔单或忽略，且都要确认', async () => {
    vi.mocked(ext.listDiscrepancies).mockResolvedValue(DISCREPANCIES)
    vi.mocked(ext.createClaimFromDiscrepancy).mockResolvedValue(ok(99))
    vi.mocked(ext.listClaims).mockResolvedValue(CLAIMS)
    vi.mocked(ext.claimSummary).mockResolvedValue(ok({ total: 2, candidate: 1 }))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '费用差异')
    expect(byText(wrapper, 'INBOUND_SHORTAGE')).toBe(true)
    await clickBtn(wrapper, '生成索赔单')
    expect(ext.createClaimFromDiscrepancy).not.toHaveBeenCalled()
    await clickBtn(wrapper, '确认执行')
    expect(ext.createClaimFromDiscrepancy).toHaveBeenCalledWith('900000000000001000', 21)
  })

  it('索赔状态机决定操作：CANDIDATE 提交平台，ACCEPTED 才让登记赔付', async () => {
    vi.mocked(ext.listClaims).mockResolvedValue(CLAIMS)
    vi.mocked(ext.claimSummary).mockResolvedValue(ok({ total: 2, candidate: 1, accepted: 1 }))
    vi.mocked(ext.submitClaim).mockResolvedValue(ok({ id: 31, status: 'SUBMITTED' }))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '亚马逊索赔')
    const rows = wrapper.find('[data-panel="claim"]').findAll('tbody tr')
    expect(rows[0].text()).toContain('提交平台')
    expect(rows[0].text()).not.toContain('登记赔付')
    expect(rows[1].text()).toContain('登记赔付')
    await rows[0].findAll('button')[0].trigger('click')
    await flushPromises()
    expect(ext.submitClaim).toHaveBeenCalledWith(31, '900000000000001000')
  })

  it('索赔对账把「只有平台有」和「只有系统有」分开列出', async () => {
    vi.mocked(ext.listClaims).mockResolvedValue(CLAIMS)
    vi.mocked(ext.claimSummary).mockResolvedValue(ok({ total: 2 }))
    vi.mocked(ext.reconcileClaims).mockResolvedValue(ok({
      shopId: 1, matched: 3, platformAdjustmentCount: 5, platformTotal: 50, systemTotal: 30,
      difference: 20,
      platformOnly: [{ reference: 'ADJ-9', sku: 'SKU-Z', amount: 20, currency: 'USD', postedAt: '2026-09-20' }],
      systemOnly: [], warnings: ['平台侧有 2 笔未匹配']
    }))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '亚马逊索赔')
    await clickBtn(wrapper, '与平台对账')
    expect(byText(wrapper, '只有平台有')).toBe(true)
    expect(byText(wrapper, 'ADJ-9')).toBe(true)
    expect(byText(wrapper, '只有系统有')).toBe(true)
    expect(byText(wrapper, '平台侧有 2 笔未匹配')).toBe(true)
  })

  it('采购域降级时补数按钮不得报成功：必须显示结果不完整', async () => {
    vi.mocked(ext.generateProcurementVouchers).mockResolvedValue({
      code: 200, message: '操作成功',
      data: { shopId: 1, scanned: 0, generated: 0, existing: 0, remoteDegraded: true, remoteMessage: 'connection refused' }
    } as any)
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, 'VAT 与凭证补数')
    await clickBtn(wrapper, '补采购成本凭证')
    expect(byText(wrapper, '降级')).toBe(true)
    expect(byText(wrapper, '别当成没有成本')).toBe(true)
    expect(byText(wrapper, 'connection refused')).toBe(true)
  })

  it('结算凭证补数命中扫描上限时提示再跑一次，而不是「补完了」', async () => {
    vi.mocked(ext.generateSettlementVouchers).mockResolvedValue(ok({
      shopId: 1, scanned: 5000, feeVouchers: 100, refundVouchers: 5, existing: 2,
      skippedByKind: 4800, skippedNoCurrency: 3, skippedZeroAmount: 1, capped: true
    }))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, 'VAT 与凭证补数')
    await clickBtn(wrapper, '补结算扣费/退款凭证')
    // 报告把「还有没扫完」显示成结论，而不是只给一个成功提示（toast 在全局容器里，不在本组件）
    expect(byText(wrapper, '是否还有未处理')).toBe(true)
    expect(byText(wrapper, '是，需再跑一次')).toBe(true)
  })

  it('单品利润成本缺失显示「缺」，不显示 0 成本', async () => {
    vi.mocked(ext.skuProfit).mockResolvedValue(ok({
      shopId: 1, skuCount: 2, costDataComplete: false, incompleteSkuCount: 1,
      totals: { revenue: 200, profit: 40 },
      entries: [
        { sku: 'SKU-A', unitsSold: 5, revenue: 200, cogs: 100, profit: 40, profitIsComplete: true },
        { sku: 'SKU-B', unitsSold: 1, revenue: 0, cogs: null, profit: null, costMissing: true, profitIsComplete: false }
      ],
      warnings: []
    }))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '单品利润')
    await clickBtn(wrapper, '计算单品利润')
    expect(byText(wrapper, '成本不全的 SKU')).toBe(true)
    expect(byText(wrapper, '缺')).toBe(true)
    expect(byText(wrapper, '不完整')).toBe(true)
  })

  it('VAT 后端字符串原样显示，不在前端解析成数字', async () => {
    vi.mocked(ext.vatRate).mockResolvedValue(ok('0.19'))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, 'VAT 与凭证补数')
    await clickBtn(wrapper, '查税率')
    expect(wrapper.find('.vat-line').text()).toBe('0.19')
  })

  it('服务端标记截断时给「加载更多」，并带上返回的 cursor', async () => {
    vi.mocked(ext.listClaims).mockResolvedValue(ok(CLAIMS.data, pageOf('v1:31')))
    vi.mocked(ext.claimSummary).mockResolvedValue(ok({ total: 2 }))
    const wrapper = mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '亚马逊索赔')
    expect(byText(wrapper, '结果已被服务端截断')).toBe(true)
    await clickBtn(wrapper, '加载更多')
    expect(ext.listClaims).toHaveBeenLastCalledWith('900000000000001000',
      expect.objectContaining({ cursor: 'v1:31' }))
  })

  it('费用差异分区带出采购域的入库短收清单，并把「成本没记币种」说在明处', async () => {
    vi.mocked(ext.listDiscrepancies).mockResolvedValue(DISCREPANCIES)
    const wrapper = await mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '费用差异')
    expect(listReceiptShortages).toHaveBeenCalledWith('900000000000001000', expect.anything())
    const panel = wrapper.find('[data-panel="discrepancy"]')
    expect(panel.text()).toContain('FBA18ABC')
    expect(panel.text()).toContain('12')
    expect(panel.text()).toContain('单位成本是我方口径且没有记币种')
  })

  it('按短收行登记：事实带入表单，币种必须人工确认才提交', async () => {
    vi.mocked(ext.listDiscrepancies).mockResolvedValue(DISCREPANCIES)
    vi.mocked(ext.intakeInboundShortage).mockResolvedValue(ok(77))
    const wrapper = await mount(Finance, { global: globalStubs })
    await flushPromises()
    await openTab(wrapper, '费用差异')
    await clickBtn(wrapper, '按此行登记')
    const inputs = wrapper.findAll('.form-grid input')
    expect((inputs[0].element as HTMLInputElement).value).toBe('SKU-9')
    // 货件号带的是亚马逊侧 FBA18ABC，不是内部货件号
    expect((inputs[1].element as HTMLInputElement).value).toBe('FBA18ABC')
    expect((inputs[2].element as HTMLInputElement).value).toBe('12')
    // 币种留空：不能默认 USD 就把钱报了
    await clickBtn(wrapper, '提交')
    expect(ext.intakeInboundShortage).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('必须显式选择赔付币种')
    await inputs[4].setValue('USD')
    await clickBtn(wrapper, '提交')
    expect(ext.intakeInboundShortage).toHaveBeenCalledWith('900000000000001000',
      expect.objectContaining({ sku: 'SKU-9', shipmentId: 'FBA18ABC', shortageUnits: 12, unitAmount: 12.5, currency: 'USD' }))
  })
})
