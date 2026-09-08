import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import KnowledgeBase from '../views/KnowledgeBase.vue'

// mock 知识库 API，避免触发真实请求
vi.mock('@/api/knowledge', () => ({
  uploadKnowledgeDocument: vi.fn(),
  deleteKnowledgeDocument: vi.fn(),
  searchKnowledgeBase: vi.fn()
}))

import {
  uploadKnowledgeDocument,
  deleteKnowledgeDocument,
  searchKnowledgeBase
} from '@/api/knowledge'

const mockedUpload = vi.mocked(uploadKnowledgeDocument)
const mockedDelete = vi.mocked(deleteKnowledgeDocument)
const mockedSearch = vi.mocked(searchKnowledgeBase)

const globalStubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' }
  }
}

const findButton = (wrapper: ReturnType<typeof mount>, text: string) =>
  wrapper.findAll('button').find(b => b.text().includes(text))!

describe('KnowledgeBase 视图', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('current_shop_id', '1')
    mockedUpload.mockReset()
    mockedDelete.mockReset()
    mockedSearch.mockReset()
    mockedSearch.mockResolvedValue({ code: 200, message: 'ok', data: [] })
  })

  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('未选店铺时应提示先选择店铺', async () => {
    localStorage.clear()
    const wrapper = mount(KnowledgeBase, { shallow: true, global: globalStubs })
    await flushPromises()

    expect(wrapper.text()).toContain('请先在顶部选择店铺')
    expect(wrapper.find('.knowledge-grid').exists()).toBe(false)
  })

  it('上传成功后应展示本次上传记录并填入 docId', async () => {
    mockedUpload.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { docId: 'doc-abc-123', filename: 'sop.pdf', chunks: 4, indexed: 4 }
    })
    const wrapper = mount(KnowledgeBase, { shallow: true, global: globalStubs })
    await flushPromises()

    const file = new File(['hello'], 'sop.pdf', { type: 'application/pdf' })
    const input = wrapper.find('input[type="file"]').element as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [file] })
    await wrapper.find('input[type="file"]').trigger('change')
    await findButton(wrapper, '上传入库').trigger('click')
    await flushPromises()

    expect(mockedUpload).toHaveBeenCalledWith('1', file)
    expect(wrapper.text()).toContain('sop.pdf')
    expect(wrapper.text()).toContain('4 块')
    // 删除输入框应自动填入新 docId
    const deleteInput = wrapper.findAll('.text-input')[1].element as HTMLInputElement
    expect(deleteInput.value).toBe('doc-abc-123')
  })

  it('超 10MB 文件应在前端直接拦截', async () => {
    const wrapper = mount(KnowledgeBase, { shallow: true, global: globalStubs })
    await flushPromises()

    const big = new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'big.pdf')
    const input = wrapper.find('input[type="file"]').element as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [big] })
    await wrapper.find('input[type="file"]').trigger('change')
    await flushPromises()

    expect(wrapper.text()).toContain('文件大小不能超过10MB')
    expect(mockedUpload).not.toHaveBeenCalled()
  })

  it('检索命中应渲染文件名与片段内容', async () => {
    mockedSearch.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [
        { docId: 'doc-abc-123', filename: '退货政策.pdf', chunkIndex: 0, content: '退货政策说明', score: 2.5 }
      ]
    })
    const wrapper = mount(KnowledgeBase, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.findAll('.text-input')[0].setValue('退货政策是怎样的？')
    await findButton(wrapper, '检索').trigger('click')
    await flushPromises()

    expect(mockedSearch).toHaveBeenCalledWith('1', '退货政策是怎样的？', 5)
    expect(wrapper.text()).toContain('退货政策.pdf')
    expect(wrapper.text()).toContain('退货政策说明')
  })

  it('检索无命中应展示空状态', async () => {
    const wrapper = mount(KnowledgeBase, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.findAll('.text-input')[0].setValue('不存在的内容')
    await findButton(wrapper, '检索').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('知识库暂无相关内容')
  })

  it('删除成功后应调用删除接口', async () => {
    mockedDelete.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: { docId: 'doc-abc-123', deleted: 4 }
    })
    const wrapper = mount(KnowledgeBase, { shallow: true, global: globalStubs })
    await flushPromises()

    await wrapper.findAll('.text-input')[1].setValue('doc-abc-123')
    await findButton(wrapper, '删除').trigger('click')
    await flushPromises()

    expect(mockedDelete).toHaveBeenCalledWith('1', 'doc-abc-123')
  })
})
