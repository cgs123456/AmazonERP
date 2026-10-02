import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ProductSelection from '../views/ProductSelection.vue'

vi.mock('@/api/selection', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/selection')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})

import * as api from '@/api/selection'

const ok = <T>(data: T) => ({ code: 200, message: '操作成功', data } as any)

const SUMMARY = {
  keyword: 'yoga mat',
  marketplace: 'US',
  category: 'Sports',
  seasonality: 'STABLE',
  marketSize: 1200000,
  opportunities: []
}

const stubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' },
    Icon: true,
    Teleport: { template: '<div><slot /></div>' }
  }
}

const mountPage = async () => {
  const wrapper = mount(ProductSelection, { global: stubs })
  await flushPromises()
  return wrapper
}

describe('选品分析页的数据来源披露', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    localStorage.setItem('current_shop_id', '7')
    localStorage.setItem('shops', JSON.stringify([{ id: '7', name: 'Shop 7' }]))
    vi.mocked(api.findOpportunities).mockResolvedValue(ok([]))
    vi.mocked(api.analyzeMarket).mockResolvedValue(ok(SUMMARY))
  })

  it('页面必须写清指标是播种模拟值、会落哪两张表、以及不再默认写 1 号店', async () => {
    const wrapper = await mountPage()

    const notice = wrapper.find('.notice-zone')
    expect(notice.exists()).toBe(true)
    const text = notice.text()
    expect(text).toContain('模拟')
    expect(text).toContain('amz_selection_opportunity')
    expect(text).toContain('amz_keyword_research')
    expect(text).toContain('1 号店')
    // 披露要指名真实来源，不能只写「数据仅供参考」
    expect(text).toContain('Brand Analytics')
  })

  it('分析成功后标题挂「模拟数据」徽标（不是降级才挂）', async () => {
    const wrapper = await mountPage()

    await wrapper.find('.keyword-input').setValue('yoga mat')
    await wrapper.find('.primary-btn').trigger('click')
    await flushPromises()

    expect(api.analyzeMarket).toHaveBeenCalledTimes(1)
    expect(wrapper.find('.section-title').text()).toContain('yoga mat')
    const badge = wrapper.find('.mock-badge')
    expect(badge.text()).toBe('模拟数据')
    // 后端返回 200 且成功展示，仍要带徽标：这里的 200 本身就是模拟值
    expect(wrapper.find('.result-section').exists()).toBe(true)
  })

  it('竞品分析与关键词调研没有入口按钮', async () => {
    const wrapper = await mountPage()

    const labels = wrapper.findAll('button').map((b) => b.text())
    expect(labels.filter((t) => t.includes('竞品'))).toHaveLength(0)
    expect(labels.filter((t) => t.includes('关键词调研'))).toHaveLength(0)
  })

  it('未选店铺时的提示仍然在，披露不依赖店铺状态', async () => {
    localStorage.removeItem('current_shop_id')
    localStorage.setItem('shops', '[]')

    const wrapper = await mountPage()

    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(wrapper.find('.notice-zone').exists()).toBe(true)
  })
})
