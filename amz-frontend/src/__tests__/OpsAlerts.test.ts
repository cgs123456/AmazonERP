import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import OpsAlerts from '../views/OpsAlerts.vue'

vi.mock('@/api/opsAlerts', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/opsAlerts')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})

import * as api from '@/api/opsAlerts'

const ok = <T>(data: T, page: any = null) =>
  ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: page }) as any
const fail = (message: string) => ({ code: 400, message, data: null } as any)
const paged = (cursor = 'v1:80') =>
  ({ size: 20, returned: 2, hasMore: true, truncated: true, nextCursor: cursor, total: null })

const REVIEWS = [
  {
    id: 81, shopId: 1, asin: 'B0REVIEW01', reviewId: 'R1001', rating: 1,
    title: 'Stopped working after a week', content: 'Very disappointed.',
    reviewer: 'John D.', status: 'NEW', createTime: '2026-10-01 10:00:00'
  },
  {
    id: 80, shopId: 1, asin: 'B0REVIEW02', reviewId: null, rating: 3,
    title: null, reviewer: null, status: 'HANDLED', createTime: null
  }
]

const HIJACKS = [
  {
    id: 91, shopId: 1, asin: 'B0HIJACK01', hijackerSellerId: 'A777',
    hijackerName: 'Competitor Seller', hijackPrice: 19.99, buyBoxTaken: true,
    status: 'NEW', createTime: null
  },
  {
    id: 90, shopId: 1, asin: 'B0HIJACK02', hijackerSellerId: null, hijackerName: null,
    hijackPrice: null, buyBoxTaken: null, status: 'IGNORED', createTime: '2026-09-30 08:00:00'
  }
]

const TREND = [
  { id: 5, shopId: 1, keyword: 'wireless earbuds', asin: 'B0123456789', rank: 42, marketplace: 'US', captureTime: '2026-10-01 09:00:00' },
  { id: 6, shopId: 1, keyword: 'wireless earbuds', asin: 'B0123456789', rank: 12, marketplace: 'US', captureTime: '2026-10-02 09:00:00' },
  { id: 7, shopId: 1, keyword: 'wireless earbuds', asin: 'B0123456789', rank: 31, marketplace: null, captureTime: null }
]

const CATALOG = [
  { keyword: 'wireless earbuds', asin: 'B01', pointCount: 3, latestRank: 12, lastCaptureTime: '2026-10-03 09:00:00', marketplace: 'US' },
  { keyword: 'noise cancelling', asin: 'B01', pointCount: 1, latestRank: 7, lastCaptureTime: '2026-10-03 09:00:00', marketplace: 'US' }
]

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(api.listReviewAlerts).mockResolvedValue(ok(REVIEWS))
  vi.mocked(api.listHijackAlerts).mockResolvedValue(ok(HIJACKS))
  vi.mocked(api.handleReviewAlert).mockResolvedValue(ok(true))
  vi.mocked(api.ignoreReviewAlert).mockResolvedValue(ok(true))
  vi.mocked(api.handleHijackAlert).mockResolvedValue(ok(true))
  vi.mocked(api.ignoreHijackAlert).mockResolvedValue(ok(true))
  vi.mocked(api.getRankTrend).mockResolvedValue(ok(TREND))
  vi.mocked(api.listTrackedKeywords).mockResolvedValue(ok(CATALOG))
}

const mountPage = async () => {
  const wrapper = mount(OpsAlerts, { global: stubs })
  await flushPromises()
  return wrapper
}

const clickBtn = async (wrapper: any, label: string) => {
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
  await flushPromises()
}

const openTab = async (wrapper: any, label: string) => {
  const tab = wrapper.findAll('.tab').find((t: any) => t.text() === label)
  expect(tab, `没有「${label}」这个 Tab`).toBeTruthy()
  await tab!.trigger('click')
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

const panelText = (wrapper: any, panel: string) => {
  const p = wrapper.find(`[data-panel="${panel}"]`)
  expect(p.exists(), `没有 ${panel} 面板`).toBe(true)
  return p.text()
}

const lastCall = (fn: any) => vi.mocked(fn).mock.calls[vi.mocked(fn).mock.calls.length - 1]

describe('运营预警台', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    happy()
  })

  it('未选店铺时既不查告警也不渲染面板', async () => {
    localStorage.clear()
    const wrapper = mount(OpsAlerts, { global: stubs })
    await flushPromises()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(api.listReviewAlerts).not.toHaveBeenCalled()
    expect(wrapper.find('[data-panel="reviews"]').exists()).toBe(false)
  })

  it('说明区把边界讲清楚：扫描是造数、处置只是本地状态、IGNORED 不抑制后续扫描', async () => {
    const wrapper = await mountPage()
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('ThreadLocalRandom')
    expect(note).toContain('mock profile')
    expect(note).toContain('不会联系买家')
    expect(note).toContain('IGNORED')
    // IGNORED 落地后必须讲明它「不」做什么，否则运营会以为选了就不再报
    expect(note).toContain('不等于')
  })

  it('三个造数入口在页面上必须不存在（跟卖的处置端点已补齐，不再是假「已处理」）', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '跟卖告警')
    // 2026-10-10 起跟卖真的有 handle/ignore 端点，按钮就该在；这里守的是「不能有扫描按钮」
    expect(wrapper.find('[data-panel="hijacks"]').findAll('button')
      .filter((b: any) => b.text() === '标记已处理').length).toBe(2)
    await openTab(wrapper, '关键词排名')
    const labels = ['扫描差评', '扫描跟卖', '抓取排名', '触发扫描', '忽略告警', '标记已忽略']
    for (const label of labels) {
      expect(wrapper.findAll('button').filter((b: any) => b.text().includes(label)).length,
        `页面出现了「${label}」按钮`).toBe(0)
    }
    await openTab(wrapper, '差评告警')
    for (const label of labels) {
      expect(wrapper.findAll('button').filter((b: any) => b.text().includes(label)).length,
        `页面出现了「${label}」按钮`).toBe(0)
    }
  })

  it('首屏只拉差评告警，跟卖要等切 Tab，排名不自动查', async () => {
    const wrapper = await mountPage()
    expect(api.listReviewAlerts).toHaveBeenCalledTimes(1)
    expect(api.listReviewAlerts).toHaveBeenCalledWith('1', expect.objectContaining({ size: 20 }))
    expect(api.listHijackAlerts).not.toHaveBeenCalled()
    expect(api.getRankTrend).not.toHaveBeenCalled()

    await openTab(wrapper, '跟卖告警')
    expect(api.listHijackAlerts).toHaveBeenCalledTimes(1)
    await openTab(wrapper, '差评告警')
    expect(api.listReviewAlerts).toHaveBeenCalledTimes(1)
  })

  it('差评行渲染真实列，缺时间就写「未记录」而不是造一个时间', async () => {
    const wrapper = await mountPage()
    const panel = panelText(wrapper, 'reviews')
    expect(panel).toContain('B0REVIEW01')
    expect(panel).toContain('R1001')
    expect(panel).toContain('John D.')
    expect(panel).toContain('HANDLED')
    expect(panel).toContain('未记录')
    expect(wrapper.find('[data-panel="reviews"] tbody tr:nth-child(2) td.neg').exists()).toBe(false)
    expect(wrapper.find('[data-panel="reviews"] tbody tr:nth-child(1) td.neg').text()).toBe('1')
  })

  it('状态筛选作为参数下推，截断时下一页带上游标', async () => {
    const wrapper = await mountPage()
    await wrapper.find('[data-panel="reviews"] select').setValue('NEW')
    expect(lastCall(api.listReviewAlerts)[1]).toEqual(expect.objectContaining({ status: 'NEW' }))

    vi.mocked(api.listReviewAlerts).mockResolvedValue(ok(REVIEWS, paged('v1:80')))
    await clickBtn(wrapper, '刷新')
    expect(wrapper.find('[data-panel="reviews"] .filter-row').text()).toContain('本页不是全量')
    await clickBtn(wrapper, '下一页')
    expect(lastCall(api.listReviewAlerts)[1]).toEqual(expect.objectContaining({ cursor: 'v1:80' }))
  })

  it('列表返回业务错误时在页面顶部点名，不静默变空表', async () => {
    vi.mocked(api.listReviewAlerts).mockResolvedValue(fail('无权访问该店铺'))
    const wrapper = await mountPage()
    expect(wrapper.find('.error-zone').text()).toContain('差评告警')
    expect(wrapper.find('.error-zone').text()).toContain('无权访问该店铺')
    expect(panelText(wrapper, 'reviews')).toContain('这家店没有差评告警行')
  })

  it('只有 NEW 的行可以标记已处理，已处理的按钮必须是禁用', async () => {
    const wrapper = await mountPage()
    const rows = wrapper.find('[data-panel="reviews"]').findAll('tbody tr')
    expect(rows[0].findAll('button')[0].attributes('disabled')).toBeUndefined()
    expect(rows[1].findAll('button')[0].attributes('disabled')).toBeDefined()
  })

  it('标记已处理要先确认「只是本地状态」，成功后刷新列表', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'reviews', 0, '标记已处理')
    const text = confirmText(wrapper)
    expect(text).toContain('NEW 改成 HANDLED')
    expect(text).toContain('不会发起申诉')
    expect(text).toContain('再点一次会被后端拒绝')
    await clickBtn(wrapper, '确认执行')

    expect(api.handleReviewAlert).toHaveBeenCalledWith(81)
    expect(vi.mocked(api.listReviewAlerts).mock.calls.length).toBe(2)
    expect(wrapper.find('.error-zone').exists()).toBe(false)
  })

  it('处理被后端拒绝时不刷新也不装作成功，原因要留在页面上', async () => {
    vi.mocked(api.handleReviewAlert).mockResolvedValue(fail('该告警已经是 HANDLED，没有再次处理'))
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'reviews', 0, '标记已处理')
    await clickBtn(wrapper, '确认执行')
    expect(wrapper.find('.error-zone').text()).toContain('已经是 HANDLED')
    // 没改成就不要重拉列表，否则看起来像「刷新一下就没了」
    expect(vi.mocked(api.listReviewAlerts).mock.calls.length).toBe(1)
  })

  it('跟卖行显示报价与 Buy Box 三态，并声明处置只是本地状态', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '跟卖告警')
    const panel = panelText(wrapper, 'hijacks')
    expect(panel).toContain('B0HIJACK01')
    expect(panel).toContain('Competitor Seller')
    expect(panel).toContain('已被抢走')
    expect(panel).toContain('未知')
    expect(panel).toContain('处置只改本地状态')
    expect(wrapper.find('[data-panel="hijacks"] tbody tr:nth-child(1) td.neg').text()).toBe('已被抢走')
  })

  it('跟卖告警可以标记已处理：确认框说明不会对跟卖方动作，成功后刷新', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '跟卖告警')
    await rowBtn(wrapper, 'hijacks', 0, '标记已处理')
    const text = confirmText(wrapper)
    expect(text).toContain('NEW 改成 HANDLED')
    expect(text).toContain('不会对跟卖方发起任何动作')
    await clickBtn(wrapper, '确认执行')

    expect(api.handleHijackAlert).toHaveBeenCalledWith(91)
    expect(vi.mocked(api.listHijackAlerts).mock.calls.length).toBe(2)
    expect(wrapper.find('.error-zone').exists()).toBe(false)
  })

  it('跟卖告警可以忽略，且确认框说明不会阻止后扫描重建', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '跟卖告警')
    await rowBtn(wrapper, 'hijacks', 0, '忽略')
    const text = confirmText(wrapper)
    expect(text).toContain('NEW 改成 IGNORED')
    expect(text).toContain('不会阻止后续扫描')
    await clickBtn(wrapper, '确认执行')

    expect(api.ignoreHijackAlert).toHaveBeenCalledWith(91)
  })

  it('差评告警可以忽略：走 IGNORED 而不是假的处理记录', async () => {
    const wrapper = await mountPage()
    await rowBtn(wrapper, 'reviews', 0, '忽略')
    const text = confirmText(wrapper)
    expect(text).toContain('NEW 改成 IGNORED')
    expect(text).toContain('判定为无需处理')
    await clickBtn(wrapper, '确认执行')

    expect(api.ignoreReviewAlert).toHaveBeenCalledWith(81)
    expect(api.handleReviewAlert).not.toHaveBeenCalled()
  })

  it('已处于终态的跟卖告警两个按钮都禁用', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '跟卖告警')
    // 第二行的 status 是 IGNORED
    const rows = wrapper.find('[data-panel="hijacks"]').findAll('tbody tr')
    for (const b of rows[1].findAll('button')) {
      expect(b.attributes('disabled')).toBeDefined()
    }
  })

  it('进排名 Tab 会拉关键词目录，不再要求凭记忆手输', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '关键词排名')
    expect(api.listTrackedKeywords).toHaveBeenCalledWith('1')
    const panel = panelText(wrapper, 'rank')
    expect(panel).toContain('已追踪的组合')
    expect(panel).toContain('wireless earbuds')
    expect(panel).toContain('noise cancelling')
  })

  it('从目录选一个组合会把关键词与 ASIN 填进输入框', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '关键词排名')
    const select = wrapper.find('[data-panel="rank"] .pick-row select')
    const options = select.findAll('option')
    // 第一项是「— 从目录里选一个 —」占位
    expect(options.length).toBe(3)
    await options[1].setSelected()
    await flushPromises()

    const inputs = wrapper.find('[data-panel="rank"] .form-card').findAll('input')
    expect((inputs[0].element as HTMLInputElement).value).toBe('wireless earbuds')
    expect((inputs[1].element as HTMLInputElement).value).toBe('B01')

    await clickBtn(wrapper, '查询趋势')
    expect(api.getRankTrend).toHaveBeenCalledWith('1', 'wireless earbuds', 'B01')
  })

  it('目录为空时明确说明只能手填，不假装是列表页', async () => {
    vi.mocked(api.listTrackedKeywords).mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    await openTab(wrapper, '关键词排名')
    const panel = panelText(wrapper, 'rank')
    expect(panel).toContain('目录是空的')
    expect(panel).toContain('下面只能手填')
    expect(wrapper.find('[data-panel="rank"] .pick-row select').exists()).toBe(false)
  })

  it('排名趋势必须两个条件都填才发请求，ASIN 按后端口径转大写', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '关键词排名')
    const inputs = wrapper.find('[data-panel="rank"] .form-card').findAll('input')
    expect(inputs.length).toBe(2)
    await inputs[0].setValue('wireless earbuds')
    const query = wrapper.find('[data-panel="rank"] .form-actions .page-btn')
    expect(query.attributes('disabled')).toBeDefined()

    await inputs[1].setValue('b0123456789')
    expect(query.attributes('disabled')).toBeUndefined()
    await query.trigger('click')
    await flushPromises()

    expect(api.getRankTrend).toHaveBeenCalledWith('1', 'wireless earbuds', 'B0123456789')
    expect(panelText(wrapper, 'rank')).toContain('最近 200 个点')
  })

  it('趋势的「最新/最好/最差」只由本页拿到的点算出来', async () => {
    const wrapper = await mountPage()
    await openTab(wrapper, '关键词排名')
    const inputs = wrapper.find('[data-panel="rank"] .form-card').findAll('input')
    await inputs[0].setValue('wireless earbuds')
    await inputs[1].setValue('B0123456789')
    await clickBtn(wrapper, '查询趋势')

    const metrics = wrapper.find('[data-panel="rank"] .metric-grid').text()
    expect(metrics).toContain('最新排名')
    expect(metrics).toContain('12')
    expect(metrics).toContain('42')
    expect(metrics).toContain('3')
    const panel = panelText(wrapper, 'rank')
    expect(panel).toContain('2026-10-02 09:00:00')
    // 最后一个点没有 captureTime，页面上要写「未记录」而不是留白让人误读
    expect(panel).toContain('未记录')
  })

  it('查不到点时说清是「没有抓取记录」，并且可以清空结果', async () => {
    vi.mocked(api.getRankTrend).mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    await openTab(wrapper, '关键词排名')
    const inputs = wrapper.find('[data-panel="rank"] .form-card').findAll('input')
    await inputs[0].setValue('phone case')
    await inputs[1].setValue('B0OTHER0001')
    await clickBtn(wrapper, '查询趋势')
    expect(panelText(wrapper, 'rank')).toContain('没有排名记录')
    expect(panelText(wrapper, 'rank')).toContain('不等于「没有排名」')
    expect(wrapper.find('[data-panel="rank"] .metric-grid').exists()).toBe(false)

    await clickBtn(wrapper, '清空结果')
    expect(panelText(wrapper, 'rank')).toContain('查一个关键词 + ASIN 的排名趋势')
    expect(panelText(wrapper, 'rank')).not.toContain('没有排名记录')
  })

  it('趋势接口报业务错误时不做「查询成功但没数据」的假象', async () => {
    vi.mocked(api.getRankTrend).mockResolvedValue(fail('keyword 不能为空'))
    const wrapper = await mountPage()
    await openTab(wrapper, '关键词排名')
    const inputs = wrapper.find('[data-panel="rank"] .form-card').findAll('input')
    await inputs[0].setValue('wireless earbuds')
    await inputs[1].setValue('B0123456789')
    await clickBtn(wrapper, '查询趋势')
    expect(wrapper.find('.error-zone').text()).toContain('keyword 不能为空')
    expect(wrapper.find('[data-panel="rank"] .metric-grid').exists()).toBe(false)
    expect(panelText(wrapper, 'rank')).not.toContain('没有排名记录')
  })
})
