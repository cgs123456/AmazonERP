import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AdSearchTerms from '../views/AdSearchTerms.vue'

vi.mock('@/api/adSearchTerms', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/adSearchTerms')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' && k !== 'asText' && k !== 'asNumber' ? vi.fn() : v
  })
  return out
})

import * as api from '@/api/adSearchTerms'

const ok = <T>(data: T, page: any = null) =>
  ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: page }) as any
const fail = (message: string) => ({ code: 500, message, data: null } as any)
const paged = (n: number) => ({ size: n, returned: n, hasMore: true, truncated: true, nextCursor: 'v1:7|88', total: null })

const RULE = {
  id: 41, shopId: 1, ruleName: '高ACoS自动暂停', ruleType: 'KEYWORD_PAUSE',
  scope: 'CAMPAIGN', scopeValue: 'camp-777', conditionField: 'ACOS', conditionOp: 'GT',
  conditionValue: 50, conditionValue2: null, action: 'PAUSE', actionValue: null,
  timeWindow: 14, priority: 10, enabled: 1, lastExecuted: null
}
const RULE2 = { ...RULE, id: 42, ruleName: 'BETWEEN 缺上界', enabled: 0, scope: null, scopeValue: null, conditionOp: 'BETWEEN' }
const TERMS = [
  { id: 1, shopId: 1, campaignId: 'camp-777', searchTerm: 'yoga mat', matchType: 'EXACT',
    impressions: 1000, clicks: 20, cost: 40, sales: 10, orders: 1, acos: 400, cr: 5, reportDate: '2026-09-20' }
]
const EXEC = {
  ruleId: 41, ruleName: '高ACoS自动暂停', ruleType: 'KEYWORD_PAUSE', actionCount: 1,
  appliedToAdAccount: false,
  note: '本接口只产出建议清单，未调用广告 API',
  matchedActions: [{ searchTerm: 'yoga mat', matchedValue: 400, action: 'PAUSE', suggestion: '暂停该搜索词所在投放', applied: false }]
}
const ANALYZE_EMPTY = {
  shopId: 1, analysisPeriod: 7, scannedRows: 0, totalSearchTerms: 0, convertingTerms: 0,
  wasteTerms: 0, highAcosTerms: 0, lowCrTerms: 0, totalCost: 0, totalSales: 0, wasteCost: 0,
  overallAcos: 0, topConvertingTerms: [], topWasteTerms: []
}

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(api.listRules).mockResolvedValue(ok([RULE, RULE2]))
  vi.mocked(api.listSearchTerms).mockResolvedValue(ok(TERMS))
  vi.mocked(api.listConvertingTerms).mockResolvedValue(ok([]))
  vi.mocked(api.reverseLookupAsin).mockResolvedValue(ok([]))
  vi.mocked(api.analyzeSearchTerms).mockResolvedValue(ok(ANALYZE_EMPTY))
  vi.mocked(api.clusterSearchTerms).mockResolvedValue(
    ok({ shopId: 1, scannedRows: 0, totalClusters: 0, topClusters: [] }))
  vi.mocked(api.executeRule).mockResolvedValue(ok(EXEC))
  vi.mocked(api.executeRules).mockResolvedValue(
    ok({ shopId: 1, rulesExecuted: 2, totalActions: 1, ruleResults: [EXEC] }))
  vi.mocked(api.createRule).mockResolvedValue(ok({ ...RULE, id: 43 }))
  vi.mocked(api.updateRule).mockResolvedValue(ok(RULE))
  vi.mocked(api.toggleRule).mockResolvedValue(ok(true))
  vi.mocked(api.deleteRule).mockResolvedValue(ok(true))
  vi.mocked(api.saveSearchTerm).mockResolvedValue(ok(TERMS[0]))
  vi.mocked(api.extractConvertingTerms).mockResolvedValue(ok([]))
  vi.mocked(api.saveAsinKeywords).mockResolvedValue(ok([]))
}

const mountPage = async () => {
  const wrapper = mount(AdSearchTerms, { global: stubs })
  await flushPromises()
  return wrapper
}

const openTab = async (wrapper: any, label: string) => {
  const btn = wrapper.findAll('.tab').find((b: any) => b.text() === label)
  expect(btn, `分区按钮不存在：${label}`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

const clickBtn = async (wrapper: any, label: string) => {
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
  await flushPromises()
}

const rowBtn = async (wrapper: any, panel: string, index: number, label: string) => {
  const rows = wrapper.find(`[data-panel="${panel}"]`).findAll('tbody tr')
  expect(rows.length, `${panel} 行数不足`).toBeGreaterThan(index)
  const btn = rows[index].findAll('button').find((b: any) => b.text() === label)
  expect(btn, `第 ${index} 行没有「${label}」`).toBeTruthy()
  await btn!.trigger('click')
  await flushPromises()
}

const confirmText = (wrapper: any) => {
  const box = wrapper.find('.modal')
  expect(box.exists(), '没有出现二次确认框').toBe(true)
  return box.text()
}

/** 打开新建规则表单并填上必填的规则名称（其余字段留默认值） */
const openRuleForm = async (wrapper: any) => {
  await clickBtn(wrapper, '新建规则')
  const form = wrapper.find('.form-card')
  await form.findAll('input')[0].setValue('测试规则')
  return form
}

describe('搜索词分析与广告规则页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    happy()
  })

  it('未选店铺时不发起任何查询，并提示先选店铺', async () => {
    localStorage.clear()
    const wrapper = mount(AdSearchTerms, { global: stubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(api.listRules).not.toHaveBeenCalled()
    expect(api.listSearchTerms).not.toHaveBeenCalled()
  })

  it('边界说明把「没有自动来源」「只产出建议」「没有调度器」都讲出来', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('没有自动来源')
    expect(note).toContain('只产出建议')
    expect(note).toContain('没有调度器')
    expect(note).toContain('分类标签')
  })

  it('规则列表按后端语义渲染判定条件与作用范围', async () => {
    const wrapper = await mountPage()
    const panel = wrapper.find('[data-panel="rules"]')
    expect(api.listRules).toHaveBeenCalledWith('1', expect.objectContaining({ ruleType: undefined }))
    expect(panel.text()).toContain('ACOS GT 50')
    expect(panel.text()).toContain('CAMPAIGN=camp-777')
    expect(panel.text()).toContain('全店搜索词')
    // BETWEEN 没上界的规则必须看得见「缺上界」，不能显示成一条正常规则
    expect(panel.text()).toContain('缺上界')
  })

  it('出建议要先确认，确认文案不许声称已执行', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'rules', 0, '出建议')
    expect(api.executeRule).not.toHaveBeenCalled()
    expect(confirmText(wrapper)).toContain('不会暂停投放')
    await clickBtn(wrapper, '确认执行')
    expect(api.executeRule).toHaveBeenCalledWith(41)
  })

  it('执行结果按后端标记展示，不自己宣布已下发', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'rules', 0, '出建议')
    await clickBtn(wrapper, '确认执行')
    const card = wrapper.find('.result-card')
    expect(card.exists()).toBe(true)
    expect(card.text()).toContain('仅建议，未下发广告账号')
    expect(card.text()).toContain('暂停该搜索词所在投放')
    expect(card.text()).toContain('未调用广告 API')
    // 执行会更新「上次执行」，所以必须重查列表
    expect(vi.mocked(api.listRules).mock.calls.length).toBe(2)
  })

  it('后端如果真的下发到广告账号，页面报错而不是继续说仅建议', async () => {
    vi.mocked(api.executeRule).mockResolvedValue(ok({ ...EXEC, appliedToAdAccount: true }))
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'rules', 0, '出建议')
    await clickBtn(wrapper, '确认执行')
    expect(wrapper.find('.error-zone').text()).toContain('appliedToAdAccount')
    expect(wrapper.find('.result-card').text()).toContain('已下发广告账号')
  })

  it('停用规则的确认说明批量执行会跳过，并按 false 调用', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'rules', 0, '停用')
    expect(confirmText(wrapper)).toContain('批量执行会跳过')
    await clickBtn(wrapper, '确认执行')
    expect(api.toggleRule).toHaveBeenCalledWith(41, false)
  })

  it('删除规则必须确认后才发请求', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'rules', 0, '删除')
    expect(api.deleteRule).not.toHaveBeenCalled()
    expect(confirmText(wrapper)).toContain('物理删除')
    await clickBtn(wrapper, '确认执行')
    expect(api.deleteRule).toHaveBeenCalledWith(41)
  })

  it('BETWEEN 未给上界时保存按钮不可用，给了才创建且带上当前店铺', async () => {
    const wrapper = await mountPage()
    const form = await openRuleForm(wrapper)
    // select 顺序：条件字段 / 比较方式 / 动作 / 作用范围
    const selects = form.findAll('select')
    await selects[1].setValue('BETWEEN')
    const bounds = form.findAll('input[type="number"]')
    await bounds[0].setValue('10')
    await flushPromises()
    // 只填了下界：按后端的判定这条规则永远不命中，所以按钮必须还是禁用
    expect(wrapper.find('.form-actions .page-btn').attributes('disabled')).toBeDefined()
    await bounds[1].setValue('80')
    await clickBtn(wrapper, '创建规则')
    expect(api.createRule).toHaveBeenCalledTimes(1)
    const body = vi.mocked(api.createRule).mock.calls[0][0] as any
    expect(body.shopId).toBe(1)
    expect(body.ruleName).toBe('测试规则')
    expect(body.conditionValue2).toBe(80)
    expect(body.id).toBeUndefined()
    expect(wrapper.find('.form-card').exists()).toBe(false)
  })

  it('后端拒绝规则时不关表单也不刷新列表', async () => {
    vi.mocked(api.createRule).mockResolvedValue(fail('阈值不能为空：判定会静默恒为 false'))
    const wrapper = await mountPage()
    const form = await openRuleForm(wrapper)
    await form.findAll('input[type="number"]')[0].setValue('50')
    await clickBtn(wrapper, '创建规则')
    expect(wrapper.find('.error-zone').text()).toContain('阈值不能为空')
    expect(wrapper.find('.form-card').exists()).toBe(true)
    expect(vi.mocked(api.listRules).mock.calls.length).toBe(1)
  })

  it('搜索词列表为空时说明是通道缺失而不是投放没词', async () => {
    vi.mocked(api.listSearchTerms).mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    await openTab(wrapper, '搜索词报表')
    expect(wrapper.find('[data-panel="terms"]').text()).toContain('没有 SP-API 搜索词报表通道')
  })

  it('录入搜索词把空数值字段留空而不是发 0，成功后刷新列表', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '搜索词报表')
    await clickBtn(wrapper, '录入搜索词')
    const form = wrapper.find('.form-card')
    // input 顺序：活动 ID / 搜索词 / 报表日期 / 曝光 / 点击 / 花费 / 销售额 / 订单
    const inputs = form.findAll('input')
    await inputs[0].setValue('camp-777')
    await inputs[1].setValue('running shoes')
    await clickBtn(wrapper, '保存这一行')
    const body = vi.mocked(api.saveSearchTerm).mock.calls[0][0] as any
    expect(body.shopId).toBe(1)
    expect(body.searchTerm).toBe('running shoes')
    expect(body.impressions).toBeUndefined()
    expect(body.cost).toBeUndefined()
    expect(vi.mocked(api.listSearchTerms).mock.calls.length).toBe(2)
  })

  it('截断时必须显式给出下一页入口并带上游标', async () => {
    vi.mocked(api.listSearchTerms).mockResolvedValue(ok(TERMS, paged(1)))
    const wrapper = await mountPage()
    await openTab(wrapper, '搜索词报表')
    const panel = wrapper.find('[data-panel="terms"]')
    expect(panel.text()).toContain('本页不是全量')
    await clickBtn(wrapper, '下一页')
    expect(api.listSearchTerms).toHaveBeenLastCalledWith('1',
      expect.objectContaining({ cursor: 'v1:7|88' }))
  })

  it('分析扫描到 0 行时不能把一堆 0 说成表现良好', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '综合分析')
    await clickBtn(wrapper, '重新分析')
    expect(api.analyzeSearchTerms).toHaveBeenCalledWith('1', expect.objectContaining({ days: 7 }))
    expect(wrapper.find('[data-panel="analyze"]').text()).toContain('不是「表现良好」')
  })

  it('聚类结果按词根给出覆盖搜索词数与订单', async () => {
    vi.mocked(api.clusterSearchTerms).mockResolvedValue(ok({
      shopId: 1, scannedRows: 3, totalClusters: 2,
      topClusters: [
        { root: 'mat', termCount: 2, totalImpressions: 900, totalClicks: 18, totalCost: 30, totalSales: 12, totalOrders: 3 },
        { root: 'yoga', termCount: 1, totalImpressions: 100, totalClicks: 2, totalCost: 10, totalSales: 0, totalOrders: 0 }
      ]
    }))
    const wrapper = await mountPage()
    await openTab(wrapper, '词根聚类')
    await clickBtn(wrapper, '重新聚类')
    const text = wrapper.find('[data-panel="cluster"]').text()
    expect(text).toContain('mat')
    expect(text).toContain('yoga')
    expect(wrapper.findAll('[data-panel="cluster"] tbody tr').length).toBe(2)
  })

  it('提取出单词要确认窗口口径，成功后刷新词库', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '出单词库')
    await clickBtn(wrapper, '按窗口重算词库')
    expect(api.extractConvertingTerms).not.toHaveBeenCalled()
    expect(confirmText(wrapper)).toContain('不会重复累加订单')
    await clickBtn(wrapper, '确认执行')
    expect(api.extractConvertingTerms).toHaveBeenCalledWith('1', 7)
    expect(vi.mocked(api.listConvertingTerms).mock.calls.length).toBe(2)
  })

  it('ASIN 空结果说明这张表只由导入写入', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, 'ASIN 反查')
    const inputs = wrapper.find('[data-panel="asin"]').findAll('input')
    await inputs[0].setValue('B0ABC12345')
    await clickBtn(wrapper, '反查')
    expect(api.reverseLookupAsin).toHaveBeenCalledWith('1', 'B0ABC12345', expect.objectContaining({ size: 20 }))
    expect(wrapper.find('[data-panel="asin"]').text()).toContain('该表只由批量导入写入')
  })

  it('批量导入：非法 JSON 与非数组都不发请求，合法时逐行打当前店铺', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, 'ASIN 反查')
    await clickBtn(wrapper, '批量导入')
    const box = () => wrapper.find('.json-box')

    await box().setValue('{不是JSON')
    await clickBtn(wrapper, '导入')
    expect(confirmText(wrapper)).toContain('覆盖写入')
    await clickBtn(wrapper, '确认执行')
    expect(api.saveAsinKeywords).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('JSON 解析失败')

    // 导入区保持展开，直接换内容重试
    await box().setValue('{"asin":"B0ABC12345"}')
    await clickBtn(wrapper, '导入')
    await clickBtn(wrapper, '确认执行')
    expect(api.saveAsinKeywords).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('需要的是 JSON 数组')

    await box().setValue('[{"asin":"b0abc12345","keyword":"yoga mat"},{"asin":"B0XYZ","keyword":"mat"}]')
    await clickBtn(wrapper, '导入')
    await clickBtn(wrapper, '确认执行')
    expect(api.saveAsinKeywords).toHaveBeenCalledWith([
      { asin: 'b0abc12345', keyword: 'yoga mat', shopId: 1 },
      { asin: 'B0XYZ', keyword: 'mat', shopId: 1 }
    ])
    expect(wrapper.find('.json-box').exists()).toBe(false)
  })

  it('列表接口返回非 200 时清空列表并给错误条', async () => {
    vi.mocked(api.listRules).mockResolvedValue(fail('店铺不存在或无权访问'))
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('店铺不存在或无权访问')
    expect(wrapper.findAll('[data-panel="rules"] tbody tr').length).toBe(1)
    expect(wrapper.find('[data-panel="rules"]').text()).toContain('这家店还没有规则')
  })
})
