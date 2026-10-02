import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import OrderAudit from '../views/OrderAudit.vue'

// API 层整体 mock：候选枚举（RULE_ACTIONS 等）保留真实值，函数换成 vi.fn()
vi.mock('@/api/orderAudit', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/orderAudit')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})

import * as audit from '@/api/orderAudit'

const SHOP = '900000000000001000'
const ok = <T>(data: T) => ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: null }) as any

const RULES = ok([
  { id: 1, shopId: 1, ruleName: 'PO Box地址检测', ruleType: 'ADDRESS_CHECK', conditionField: 'shipping_address',
    conditionOp: 'CONTAINS', conditionValue: 'PO Box', action: 'FLAG', priority: 1, enabled: 1,
    description: '检测PO Box地址标记高风险', actionParams: null },
  { id: 2, shopId: 1, ruleName: '同地址合并', ruleType: 'MERGE', conditionField: 'shipping_address',
    conditionOp: 'EQ', conditionValue: '__SAME_ADDRESS__', action: 'MERGE', priority: 10, enabled: 0,
    description: null, actionParams: null },
  { id: 3, shopId: 1, ruleName: '高额拦截', ruleType: 'AMOUNT_CHECK', conditionField: 'final_price',
    conditionOp: 'GT', conditionValue: '500', action: 'BLOCK', priority: 5, enabled: 1,
    description: '金额>500 拦截', actionParams: null }
])

const AUDIT_RESULT = ok({
  orderId: '114-1111111-1111111', shopId: 1, verdict: 'REVIEW',
  alerts: [{ ruleId: 1, ruleName: 'PO Box地址检测', ruleType: 'ADDRESS_CHECK', action: 'FLAG', description: '检测PO Box地址标记高风险' }],
  actions: ['FLAG', 'MERGE'], alertCount: 1,
  unevaluatedRules: [{ ruleId: 2, ruleName: '同地址合并', action: 'MERGE', conditionField: 'shipping_address',
    conditionOp: 'EQ', reason: '条件字段 shipping_address 取不到值（字段未接入或订单该值为空）' }],
  unevaluatedCount: 1, advisoryActions: ['MERGE'],
  advisoryNote: 'MERGE/SPLIT 只是规则建议：本系统没有合并/拆单实现，amz_order_split_log 全仓没有任何插入点，订单数据不会被这条规则改变。',
  auditTime: '2026-10-02T10:00:00'
})

const BATCH_RESULTS = ok([
  { orderId: '114-1', shopId: 1, verdict: 'PASS', alerts: [], actions: [], alertCount: 0,
    unevaluatedRules: [], unevaluatedCount: 0, auditTime: '2026-10-02T10:00:00' },
  { orderId: '114-2', shopId: 1, verdict: 'BLOCKED', alerts: [{ ruleId: 3, ruleName: '高额拦截', ruleType: 'AMOUNT_CHECK', action: 'BLOCK', description: '金额>500 拦截' }],
    actions: ['BLOCK'], alertCount: 1, unevaluatedRules: [], unevaluatedCount: 0, auditTime: '2026-10-02T10:00:01' }
])

const ROUTING = ok({
  id: 88, shopId: 1, amazonOrderId: '114-1111111-1111111', sku: 'SKU-1', asin: 'B0TEST01', quantity: 2,
  warehouseId: null, warehouseName: null, warehouseType: 'FBA', carrierName: null, trackingNo: null,
  shippingCost: null, selectedReason: 'FBA主配送国家，仅仓库类型建议；具体发货仓未解析，需物流模块确认',
  routeTime: '2026-10-02T10:00:00'
})

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(audit.listRules).mockResolvedValue(RULES)
  vi.mocked(audit.createRule).mockResolvedValue(ok({ id: 4 }))
  vi.mocked(audit.updateRule).mockResolvedValue(ok({ id: 1 }))
  vi.mocked(audit.toggleRule).mockResolvedValue(ok(true))
  vi.mocked(audit.deleteRule).mockResolvedValue(ok(true))
  vi.mocked(audit.auditOrder).mockResolvedValue(AUDIT_RESULT)
  vi.mocked(audit.batchAudit).mockResolvedValue(BATCH_RESULTS)
  vi.mocked(audit.routeOrder).mockResolvedValue(ROUTING)
  vi.mocked(audit.listSplitLogs).mockResolvedValue(ok([]))
}

const mountPage = async () => {
  const wrapper = mount(OrderAudit, { global: stubs })
  await flushPromises()
  return wrapper
}

const openTab = async (wrapper: any, label: string) => {
  const btn = wrapper.findAll('.tab').find((b: any) => b.text() === label)
  expect(btn, `分区按钮不存在：${label}`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

/** 行内按钮：同名动作每行一个，必须按 (分区, 行号, 标签) 定位，不能靠全局唯一 */
const rowBtn = async (wrapper: any, panelSelector: string, rowIndex: number, label: string) => {
  const rows = wrapper.find(panelSelector).findAll('tbody tr')
  expect(rows.length, `分区 ${panelSelector} 行数不足 ${rowIndex + 1}`).toBeGreaterThan(rowIndex)
  const btn = rows[rowIndex].findAll('button').find((b: any) => b.text() === label)
  expect(btn, `第 ${rowIndex + 1} 行没有按钮「${label}」`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

const clickBtn = async (wrapper: any, label: string) => {
  // 标签必须唯一：分区名与按钮名同名时，findAll 的第一条命中的是 Tab 而不是动作按钮
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
  await flushPromises()
}

describe('OrderAudit 视图（订单审单）', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', SHOP)
    vi.clearAllMocks()
    happy()
  })

  it('未选店铺时不请求审单端点', async () => {
    localStorage.clear()
    const wrapper = await mountPage()
    expect(wrapper.find('.shop-tip').text()).toContain('请先在右上角选择店铺')
    expect(wrapper.findAll('.tab').length).toBe(0)
    expect(audit.listRules).not.toHaveBeenCalled()
  })

  it('五个分区一次只渲染一个，规则与日志按需加载，其余分区不发请求', async () => {
    const wrapper = await mountPage()
    expect(wrapper.findAll('.tab').length).toBe(5)
    expect(wrapper.findAll('.tab-panel').length).toBe(1)
    expect(audit.listRules).toHaveBeenCalledTimes(1)
    expect(audit.listSplitLogs).not.toHaveBeenCalled()

    await openTab(wrapper, '单订单审单')
    expect(wrapper.find('[data-panel="audit"]').exists()).toBe(true)
    expect(wrapper.findAll('.tab-panel').length).toBe(1)

    await openTab(wrapper, '批量审单')
    expect(wrapper.findAll('.tab-panel').length).toBe(1)
    expect(wrapper.find('[data-panel="batch"]').exists()).toBe(true)

    await openTab(wrapper, '发货路由')
    expect(wrapper.find('[data-panel="route"]').exists()).toBe(true)
    expect(audit.routeOrder).not.toHaveBeenCalled()

    await openTab(wrapper, '拆分日志')
    expect(wrapper.find('[data-panel="split"]').exists()).toBe(true)
    expect(audit.listSplitLogs).toHaveBeenCalledTimes(1)
    expect(audit.listSplitLogs).toHaveBeenCalledWith(SHOP, undefined)

    await openTab(wrapper, '拆分日志')
    expect(audit.listSplitLogs).toHaveBeenCalledTimes(1)
  })

  it('三条边界常驻页面：不改订单数据、地址字段判不出来、仓库名未解析', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('不修改订单数据')
    expect(note).toContain('shipping_address')
    expect(note).toContain('零插入点')
    expect(note).toContain('warehouse_name')
  })

  it('规则表原样显示后端条件，判不出来的字段与仅建议动作各自标注', async () => {
    const wrapper = await mountPage()
    const panel = wrapper.find('[data-panel="rule"]')
    expect(panel.findAll('tbody tr').length).toBe(3)
    expect(panel.text()).toContain('PO Box地址检测')
    expect(panel.text()).toContain('final_price GT 500')
    expect(panel.findAll('.neg').map((n: any) => n.text())).toEqual(['（永远判不出来）', '（永远判不出来）'])
    expect(panel.text()).toContain('仅建议')
    expect(panel.text()).toContain('停用')
    // description 为 null 的行显示占位，不编说明
    expect(panel.findAll('.cell-clip')[1].text()).toBe('-')
    expect(panel.find('.note-line').text()).toContain('后端不分页')
  })

  it('规则为空时说明审单会一律给 PASS，不落任何示例规则', async () => {
    vi.mocked(audit.listRules).mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    expect(wrapper.find('[data-panel="rule"] .empty-row').text()).toContain('审单会一律给 PASS')
    expect(wrapper.findAll('tbody tr').length).toBe(1)
  })

  it('规则必填校验挡住空表单，默认启用并对齐后端 enabled 布尔语义', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '新建规则')
    await clickBtn(wrapper, '提交')
    expect(audit.createRule).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('必填')

    const inputs = wrapper.find('.modal').findAll('input')
    await inputs[0].setValue('低额人工复核')
    await inputs[1].setValue('AMOUNT_CHECK')
    await wrapper.find('.modal').findAll('input')[2].setValue('10')
    await clickBtn(wrapper, '提交')
    expect(audit.createRule).toHaveBeenCalledTimes(1)
    const body = vi.mocked(audit.createRule).mock.calls[0][0] as any
    expect(body.ruleName).toBe('低额人工复核')
    expect(body.conditionValue).toBe('10')
    expect(body.shopId).toBe(SHOP)
    // 后端 DDL 默认启用；新建规则不应默认停用
    expect(body.enabled).toBe(true)
    expect(body.actionParams).toBeNull()
    expect(wrapper.find('.modal-mask').exists()).toBe(false)
  })

  it('编辑规则走 PUT 并用规则 id，新建接口不被动到', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, '[data-panel="rule"]', 0, '编辑')
    await wrapper.find('.modal').findAll('input')[0].setValue('PO Box地址检测 v2')
    await clickBtn(wrapper, '提交')
    expect(audit.updateRule).toHaveBeenCalledTimes(1)
    expect(vi.mocked(audit.updateRule).mock.calls[0][0]).toBe(1)
    expect((vi.mocked(audit.updateRule).mock.calls[0][1] as any).ruleName).toBe('PO Box地址检测 v2')
    expect(audit.createRule).not.toHaveBeenCalled()
  })

  it('删除规则必须二次确认，取消时不发 DELETE', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, '[data-panel="rule"]', 0, '删除')
    expect(wrapper.find('.confirm-detail').text()).toContain('不校验该规则属于当前店铺')
    expect(audit.deleteRule).not.toHaveBeenCalled()
    await clickBtn(wrapper, '取消')
    expect(audit.deleteRule).not.toHaveBeenCalled()

    await rowBtn(wrapper, '[data-panel="rule"]', 0, '删除')
    await clickBtn(wrapper, '确认执行')
    expect(audit.deleteRule).toHaveBeenCalledWith(1)
    expect(audit.listRules).toHaveBeenCalledTimes(2)
  })

  it('启用开关以后端为准：提交后重新拉列表而不是本地翻状态', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, '[data-panel="rule"]', 0, '停用')
    expect(audit.toggleRule).toHaveBeenCalledWith(1, false)
    expect(audit.listRules).toHaveBeenCalledTimes(2)
  })

  it('审单表单只送规则能看到的字段，结果区分开命中与未判定并展示建议说明', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '单订单审单')
    await clickBtn(wrapper, '审这一单')
    expect(audit.auditOrder).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('订单号必填')

    const inputs = wrapper.find('[data-panel="audit"] .table-card').findAll('input')
    await inputs[0].setValue('114-1111111-1111111')
    await inputs[3].setValue('120.5')
    await clickBtn(wrapper, '审这一单')
    const body = vi.mocked(audit.auditOrder).mock.calls[0][1] as any
    expect(body.amazonOrderId).toBe('114-1111111-1111111')
    expect(body.finalPrice).toBe(120.5)
    // 没填的字段不能带空串进去，否则数值/字符串规则会拿空值判定
    expect('buyerName' in body).toBe(false)
    expect('marketplaceId' in body).toBe(false)

    const panel = wrapper.find('[data-panel="audit"]')
    expect(panel.text()).toContain('REVIEW')
    expect(panel.text()).toContain('命中 1 条')
    expect(panel.text()).toContain('未判定 1 条')
    expect(panel.text()).toContain('条件字段 shipping_address 取不到值')
    expect(panel.find('.advisory').text()).toContain('没有合并/拆单实现')
    expect(panel.text()).toContain('「未判定」不等于「没风险」')
  })

  it('批量审单先解析 JSON，非法输入不发请求，空数组不造结果行', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '批量审单')
    const box = wrapper.find('[data-panel="batch"] textarea')

    await box.setValue('{ 不是 JSON')
    await clickBtn(wrapper, '执行批量审单')
    expect(audit.batchAudit).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('JSON 解析失败')

    await box.setValue('{"amazonOrderId":"114-1"}')
    await clickBtn(wrapper, '执行批量审单')
    expect(audit.batchAudit).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('顶层必须是数组')

    vi.mocked(audit.batchAudit).mockResolvedValue(ok([]))
    await box.setValue('[]')
    await clickBtn(wrapper, '执行批量审单')
    expect(audit.batchAudit).toHaveBeenCalledTimes(1)
    expect(audit.batchAudit).toHaveBeenCalledWith(SHOP, [])
    expect(wrapper.find('[data-panel="batch"]').findAll('.table-card').length).toBe(1)

    // data 不是数组（异常响应）时也不允许前端补出一行
    vi.mocked(audit.batchAudit).mockResolvedValue(ok(null))
    await box.setValue('[{"amazonOrderId":"114-1"}]')
    await clickBtn(wrapper, '执行批量审单')
    expect(audit.batchAudit).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-panel="batch"]').findAll('.table-card').length).toBe(1)
    expect(wrapper.find('[data-panel="batch"]').text()).not.toContain('共')
  })

  it('批量结果按 verdict 上色并把建议类动作单列，不当成已执行', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '批量审单')
    await wrapper.find('[data-panel="batch"] textarea').setValue('[{"amazonOrderId":"114-1"}]')
    await clickBtn(wrapper, '执行批量审单')
    const panel = wrapper.find('[data-panel="batch"]')
    expect(panel.text()).toContain('共 2 条')
    expect(panel.text()).toContain('PASS')
    expect(panel.text()).toContain('BLOCKED')
    expect(panel.findAll('.status-tag').map((t: any) => t.classes().join(' ')).join(' |')).toContain('status-tag urgent')
  })

  it('路由建议要确认（会入库），取消不发请求；仓库名为空时显示未解析而不是编一个', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '发货路由')
    await clickBtn(wrapper, '生成并入库')
    expect(audit.routeOrder).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('订单号与 SKU 必填')

    const inputs = wrapper.find('[data-panel="route"] .table-card').findAll('input')
    await inputs[0].setValue('114-1111111-1111111')
    await inputs[1].setValue('SKU-1')
    await clickBtn(wrapper, '生成并入库')
    expect(wrapper.find('.confirm-detail').text()).toContain('插入一行')
    expect(audit.routeOrder).not.toHaveBeenCalled()
    await clickBtn(wrapper, '取消')
    expect(audit.routeOrder).not.toHaveBeenCalled()

    await clickBtn(wrapper, '生成并入库')
    await clickBtn(wrapper, '确认执行')
    expect(audit.routeOrder).toHaveBeenCalledTimes(1)
    const q = vi.mocked(audit.routeOrder).mock.calls[0][1] as any
    expect(q.amazonOrderId).toBe('114-1111111-1111111')
    expect(q.quantity).toBe(1)
    expect(q.country).toBe('US')

    const panel = wrapper.find('[data-panel="route"]')
    expect(panel.text()).toContain('未解析（需物流模块确认）')
    expect(panel.text()).not.toContain('FBA-Warehouse')
  })

  it('拆分日志为空时说明是真实状态，有行时按后端字段渲染', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '拆分日志')
    expect(wrapper.find('[data-panel="split"] .empty-row').text()).toContain('全仓没有 amz_order_split_log 的插入点')

    vi.mocked(audit.listSplitLogs).mockResolvedValue(ok([
      { id: 7, shopId: 1, originalOrderId: '114-1', splitOrderId: '114-1-S1', splitReason: null,
        splitItems: '[{"sku":"SKU-1"}]', operator: 'admin', splitTime: '2026-09-30T09:00:00' }
    ]))
    await clickBtn(wrapper, '刷新')
    const panel = wrapper.find('[data-panel="split"]')
    expect(panel.text()).toContain('114-1-S1')
    expect(panel.text()).toContain('admin')
    expect(panel.text()).not.toContain('全仓没有 amz_order_split_log 的插入点')
  })

  it('后端非 200 与网络异常都进错误条，规则列表清空而不是留旧数据', async () => {
    vi.mocked(audit.listRules).mockResolvedValue({ code: 500, message: '订单服务不可用', data: null } as any)
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('规则列表')
    expect(wrapper.find('.error-zone').text()).toContain('订单服务不可用')
    expect(wrapper.findAll('[data-panel="rule"] tbody tr').length).toBe(1)

    vi.mocked(audit.listSplitLogs).mockRejectedValue(new Error('network down'))
    await openTab(wrapper, '拆分日志')
    expect(wrapper.find('.error-zone').text()).toContain('network down')
  })
})
