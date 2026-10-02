import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProductSearch from '../views/ProductSearch.vue'

vi.mock('@/api/search', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/search')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})

import * as api from '@/api/search'

const ok = <T>(data: T) => ({ code: 200, message: '操作成功', data, _hiddenFields: null, _page: null }) as any

const PRODUCTS = ok([
  { id: 1, title: 'Wireless Earbuds Pro', content: 'anc', summary: '主动降噪耳机', image: 'http://img/1.jpg',
    price: 39.9, sku: 'SKU-WE-01', shopId: 7, userId: 3, user: { id: 3, nickname: '卖家A', phone: null } },
  { id: 2, title: 'Earbuds Case', content: 'case', summary: null, image: null,
    price: null, sku: null, shopId: null, userId: null, user: null }
])
const HOT = ok([{ key: 'earbuds', score: 12 }, { key: 'anc', score: 5 }])
const HISTORY = ok([{ history: 'earbuds', userId: 3 }, { history: 'anc', userId: 3 }])

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  vi.mocked(api.getHotList).mockResolvedValue(HOT)
  vi.mocked(api.getHistoryList).mockResolvedValue(HISTORY)
  vi.mocked(api.searchProducts).mockResolvedValue(PRODUCTS)
  vi.mocked(api.deleteHistory).mockResolvedValue(ok(null))
}

const mountPage = async () => {
  const wrapper = mount(ProductSearch, { global: stubs })
  await flushPromises()
  return wrapper
}

const clickBtn = async (wrapper: any, label: string) => {
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
  await flushPromises()
}

describe('ProductSearch 视图（商品搜索）', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    happy()
  })

  it('没有选中店铺也照常加载热搜与历史：这一域按登录用户归属，与店铺无关', async () => {
    const wrapper = await mountPage()
    expect(api.getHotList).toHaveBeenCalledTimes(1)
    expect(api.getHistoryList).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.shop-tip').exists()).toBe(false)
    const note = wrapper.find('.notice-zone').text()
    expect(note).toContain('登录用户')
    expect(note).toContain('amz_product')
    expect(note).toContain('不会退回示例数据')
  })

  it('空关键词不发起搜索，只给错误条', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '搜索')
    expect(api.searchProducts).not.toHaveBeenCalled()
    expect(wrapper.find('.error-zone').text()).toContain('关键词不能为空')
  })

  it('搜索成功后渲染真实字段，并刷新历史与热搜（后端这两处会被写）', async () => {
    const wrapper = await mountPage()
    await wrapper.find('input').setValue('earbuds')
    await clickBtn(wrapper, '搜索')
    expect(api.searchProducts).toHaveBeenCalledWith('earbuds')
    expect(api.getHistoryList).toHaveBeenCalledTimes(2)
    expect(api.getHotList).toHaveBeenCalledTimes(2)
    const rows = wrapper.findAll('.table-card')[1].findAll('tbody tr')
    expect(rows.length).toBe(2)
    expect(rows[0].text()).toContain('Wireless Earbuds Pro')
    expect(rows[0].text()).toContain('SKU-WE-01')
    expect(rows[0].text()).toContain('39.9')
    expect(rows[0].text()).toContain('卖家A')
    // 第二行字段为空：显示占位而不是编一个值
    expect(rows[1].findAll('td')[1].text()).toBe('-')
    expect(rows[1].findAll('td')[2].text()).toBe('-')
  })

  it('关键词里的空格会被去掉再送后端', async () => {
    const wrapper = await mountPage()
    await wrapper.find('input').setValue('  wireless case  ')
    await clickBtn(wrapper, '搜索')
    expect(api.searchProducts).toHaveBeenCalledWith('wireless case')
  })

  it('ES 返回 0 条时说明是真查过没命中，不是没查', async () => {
    vi.mocked(api.searchProducts).mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    await wrapper.find('input').setValue('不存在词')
    await clickBtn(wrapper, '搜索')
    expect(wrapper.text()).toContain('Elasticsearch 返回的 0 条')
    expect(wrapper.text()).not.toContain('还没搜索')
  })

  it('搜索报错时结果区回到「还没搜索」，不留下上一次的成功结果', async () => {
    const wrapper = await mountPage()
    await wrapper.find('input').setValue('earbuds')
    await clickBtn(wrapper, '搜索')
    expect(wrapper.text()).toContain('Wireless Earbuds Pro')

    vi.mocked(api.searchProducts).mockRejectedValue(new Error('ES connection refused'))
    await wrapper.find('input').setValue('换个词')
    await clickBtn(wrapper, '搜索')
    expect(wrapper.find('.error-zone').text()).toContain('ES connection refused')
    // 失败不能把上一次的结果继续挂着显示：那看起来像「这次也查到了」
    expect(wrapper.text()).not.toContain('Wireless Earbuds Pro')
    expect(wrapper.text()).toContain('还没搜索')
  })

  it('后端非 200 用后端的原因，不静默当空结果', async () => {
    vi.mocked(api.searchProducts).mockResolvedValue({ code: 500, message: '索引不可用', data: [] } as any)
    const wrapper = await mountPage()
    await wrapper.find('input').setValue('earbuds')
    await clickBtn(wrapper, '搜索')
    expect(wrapper.find('.error-zone').text()).toContain('商品搜索：索引不可用')
    expect(wrapper.text()).toContain('还没搜索')
  })

  it('热搜 data 为 null 时说明是「没有计数」而不是 0 分热词', async () => {
    vi.mocked(api.getHotList).mockResolvedValue(ok(null))
    const wrapper = await mountPage()
    expect(wrapper.text()).toContain('接口返回的是 null')
    expect(wrapper.text()).not.toContain('热度分')
  })

  it('热搜按名次渲染分数，点「搜这个词」把词送进搜索', async () => {
    const wrapper = await mountPage()
    const hotRows = wrapper.findAll('tbody tr').filter((r: any) => r.text().includes('搜这个词'))
    expect(hotRows.length).toBe(2)
    expect(hotRows[0].text()).toContain('earbuds')
    expect(hotRows[0].text()).toContain('12')
    await hotRows[1].findAll('button')[0].trigger('click')
    await flushPromises()
    expect(api.searchProducts).toHaveBeenCalledWith('anc')
  })

  it('历史按登录用户显示，点「再搜一次」复用同一条关键词', async () => {
    const wrapper = await mountPage()
    const historyRows = wrapper.findAll('tbody tr').filter((r: any) => r.text().includes('再搜一次'))
    expect(historyRows.length).toBe(2)
    expect(historyRows[0].text()).toContain('3')
    await historyRows[0].findAll('button')[0].trigger('click')
    await flushPromises()
    expect(api.searchProducts).toHaveBeenCalledWith('earbuds')
  })

  it('清空历史要二次确认：取消不发 DELETE，确认后重拉历史', async () => {
    const wrapper = await mountPage()
    await clickBtn(wrapper, '清空历史')
    expect(wrapper.find('.confirm-detail').text()).toContain('不可恢复')
    expect(wrapper.find('.confirm-detail').text()).toContain('2 条')
    expect(api.deleteHistory).not.toHaveBeenCalled()
    await clickBtn(wrapper, '取消')
    expect(api.deleteHistory).not.toHaveBeenCalled()

    await clickBtn(wrapper, '清空历史')
    await clickBtn(wrapper, '确认执行')
    expect(api.deleteHistory).toHaveBeenCalledTimes(1)
    // 挂载 1 次 + 删除后重拉 1 次
    expect(api.getHistoryList).toHaveBeenCalledTimes(2)
  })

  it('历史为空时清空按钮不可用，避免发一次没有意义的删除', async () => {
    vi.mocked(api.getHistoryList).mockResolvedValue(ok([]))
    const wrapper = await mountPage()
    const btn = wrapper.findAll('button').find((b: any) => b.text() === '清空历史')
    expect(btn!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('还没有搜索记录')
  })
})
