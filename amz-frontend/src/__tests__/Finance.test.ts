import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Finance from '../views/Finance.vue'
import { useToast } from '../composables/useToast'

vi.mock('@/api/finance', () => ({
  listVouchers: vi.fn(),
  syncToKingdee: vi.fn(),
  calculateProfit: vi.fn()
}))

import { listVouchers, syncToKingdee } from '@/api/finance'

const mockedListVouchers = vi.mocked(listVouchers)
const mockedSyncToKingdee = vi.mocked(syncToKingdee)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' },
    Icon: { template: '<span />' }
  }
}

const voucher = (kingdeeSyncStatus: string) => ({
  id: 101,
  voucherNo: 'VCH-101',
  shopId: 1,
  bizDate: '2026-09-25',
  summary: '测试凭证',
  debitAccount: '1001',
  creditAccount: '6001',
  originalAmount: 100,
  currency: 'USD',
  exchangeRate: 7.1,
  cnyAmount: 710,
  sourceType: 'ORDER',
  sourceNo: 'ORDER-101',
  kingdeeSyncStatus
})

describe('Finance 金蝶同步结果', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    mockedListVouchers.mockReset()
    mockedSyncToKingdee.mockReset()
    useToast().toastVisible.value = false
    useToast().toastMessage.value = ''
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('MOCK 结果必须提示模拟成功且明确未真实入账', async () => {
    mockedListVouchers
      .mockResolvedValueOnce({ code: 200, message: 'ok', data: [voucher('PENDING')] })
      .mockResolvedValueOnce({ code: 200, message: 'ok', data: [voucher('MOCK')] })
    mockedSyncToKingdee.mockResolvedValue({
      code: 200,
      message: '模拟同步完成，未真实入账',
      data: {
        status: 'MOCK',
        voucherId: 101,
        kingdeeNo: 'MOCK-101',
        message: '模拟同步完成，未真实入账'
      }
    })

    const wrapper = mount(Finance, { shallow: true, global: globalStubs })
    await flushPromises()
    await wrapper.find('.sync-btn').trigger('click')
    await flushPromises()

    expect(mockedSyncToKingdee).toHaveBeenCalledWith(101)
    expect(useToast().toastMessage.value).toBe('模拟同步完成，未真实入账')
    expect(wrapper.find('.sync-mock').text()).toBe('模拟未入账')
    expect(wrapper.text()).not.toContain('已真实入账')
  })

  it('SYNCED 结果才提示真实入账成功', async () => {
    mockedListVouchers
      .mockResolvedValueOnce({ code: 200, message: 'ok', data: [voucher('PENDING')] })
      .mockResolvedValueOnce({ code: 200, message: 'ok', data: [voucher('SYNCED')] })
    mockedSyncToKingdee.mockResolvedValue({
      code: 200,
      message: '金蝶已确认凭证入账',
      data: {
        status: 'SYNCED',
        voucherId: 101,
        kingdeeNo: 'KD-101',
        message: '金蝶已确认凭证入账'
      }
    })

    const wrapper = mount(Finance, { shallow: true, global: globalStubs })
    await flushPromises()
    await wrapper.find('.sync-btn').trigger('click')
    await flushPromises()

    expect(useToast().toastType.value).toBe('success')
    expect(useToast().toastMessage.value).toBe('金蝶已确认凭证入账')
    expect(wrapper.find('.sync-synced').text()).toBe('已入账')
  })

  it('SKIPPED 结果提示未重复调用，不得提示入账成功', async () => {
    mockedListVouchers.mockResolvedValue({ code: 200, message: 'ok', data: [voucher('SYNCING')] })
    mockedSyncToKingdee.mockResolvedValue({
      code: 200,
      message: '同步正在处理中或状态已变化，本次未重复调用金蝶',
      data: {
        status: 'SKIPPED',
        voucherId: 101,
        kingdeeNo: null,
        message: '同步正在处理中或状态已变化，本次未重复调用金蝶'
      }
    })

    const wrapper = mount(Finance, { shallow: true, global: globalStubs })
    await flushPromises()
    await wrapper.find('.sync-btn').trigger('click')
    await flushPromises()

    expect(useToast().toastType.value).toBe('info')
    expect(useToast().toastMessage.value).toContain('未重复调用金蝶')
    expect(useToast().toastMessage.value).not.toContain('已确认')
  })

  it('NOT_CONFIGURED 失败响应即使 code=400 也应展示结构化原因', async () => {
    mockedListVouchers.mockResolvedValue({ code: 200, message: 'ok', data: [voucher('FAILED')] })
    mockedSyncToKingdee.mockResolvedValue({
      code: 400,
      message: '金蝶连接器未配置完整',
      data: {
        status: 'NOT_CONFIGURED',
        voucherId: 101,
        kingdeeNo: null,
        message: '金蝶连接器未配置完整：缺少 appId'
      }
    })

    const wrapper = mount(Finance, { shallow: true, global: globalStubs })
    await flushPromises()
    await wrapper.find('.sync-btn').trigger('click')
    await flushPromises()

    expect(useToast().toastType.value).toBe('error')
    expect(useToast().toastMessage.value).toContain('缺少 appId')
    expect(useToast().toastMessage.value).not.toContain('已入账')
  })
})

describe('Finance 凭证列表截断提示', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    mockedListVouchers.mockReset()
    useToast().toastVisible.value = false
    useToast().toastMessage.value = ''
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('服务端标记 truncated 时必须展示截断提示，不得假装列表已完整', async () => {
    mockedListVouchers.mockResolvedValue({
      code: 200,
      message: '操作成功（结果已截断，请携带 nextCursor 继续翻页）',
      data: [voucher('PENDING')],
      _page: { size: 50, returned: 50, hasMore: true, truncated: true, nextCursor: 'CUR-1', total: null }
    })

    const wrapper = mount(Finance, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.truncated-tip').exists()).toBe(true)
    expect(wrapper.text()).toContain('仍有更多未加载')
    expect(mockedListVouchers).toHaveBeenCalledWith('1', undefined, 50, undefined)
  })

  it('点击加载更多必须带上服务端返回的 cursor', async () => {
    mockedListVouchers
      .mockResolvedValueOnce({
        code: 200,
        message: '操作成功（结果已截断，请携带 nextCursor 继续翻页）',
        data: [voucher('PENDING')],
        _page: { size: 50, returned: 50, hasMore: true, truncated: true, nextCursor: 'CUR-1', total: null }
      })
      .mockResolvedValueOnce({
        code: 200,
        message: '操作成功',
        data: [voucher('PENDING'), voucher('SYNCED')],
        _page: { size: 50, returned: 2, hasMore: false, truncated: false, nextCursor: null, total: null }
      })

    const wrapper = mount(Finance, { shallow: true, global: globalStubs })
    await flushPromises()
    await wrapper.find('.page-btn').trigger('click')
    await flushPromises()

    expect(mockedListVouchers).toHaveBeenLastCalledWith('1', undefined, 50, 'CUR-1')
    // 第二页没有更多：提示必须消失，否则用户会以为还没取完
    expect(wrapper.find('.truncated-tip').exists()).toBe(false)
    expect(wrapper.text()).toContain('已加载 3 条')
  })

  it('未截断时不展示提示，列表按原样渲染', async () => {
    mockedListVouchers.mockResolvedValue({
      code: 200,
      message: '操作成功',
      data: [voucher('SYNCED')],
      _page: { size: 50, returned: 1, hasMore: false, truncated: false, nextCursor: null, total: null }
    })

    const wrapper = mount(Finance, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.truncated-tip').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('仍有更多未加载')
  })

  it('_page 缺失时按未知处理，不得默认当成已取完', async () => {
    mockedListVouchers.mockResolvedValue({
      code: 200,
      message: '操作成功',
      data: [voucher('PENDING')]
    })

    const wrapper = mount(Finance, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.find('.truncated-tip').exists()).toBe(false)
    expect(wrapper.text()).toContain('已加载 1 条')
  })
})