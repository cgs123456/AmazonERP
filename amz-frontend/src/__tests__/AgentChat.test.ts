import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AgentChat from '../components/AgentChat.vue'

// 组件走统一 request 实例（../api/auth 默认导出），此处整体 mock 该模块
vi.mock('../api/auth', () => ({
  default: {
    post: vi.fn()
  }
}))

import request from '../api/auth'

const mockedPost = vi.mocked(request.post)

// SSE fetch 测试替身：按给定分块构造 ReadableStream 响应
const sseStream = (chunks: string[]) =>
  new ReadableStream<Uint8Array>({
    start(controller) {
      const enc = new TextEncoder()
      for (const c of chunks) controller.enqueue(enc.encode(c))
      controller.close()
    }
  })

const mockFetchSse = (chunks: string[]) =>
  vi.fn().mockResolvedValue({ ok: true, body: sseStream(chunks) })

// 公共 stubs：避免 Teleport/Transition/Icon 在测试环境中的副作用
const globalStubs = {
  stubs: {
    Teleport: { template: '<div><slot /></div>' },
    Transition: { template: '<div><slot /></div>' },
    Icon: true
  }
}

describe('AgentChat 组件', () => {
  beforeEach(() => {
    // 登录守卫需要 token；未登录时组件直接提示而不发起请求
    localStorage.setItem('token', 'test-token')
    mockedPost.mockReset()
    // 默认模拟后端接口不可用，使组件降级到 generateMockReply
    mockedPost.mockRejectedValue(new Error('test: backend unavailable'))
    // 默认流式建连失败，走 POST 回退路径（各 POST 用例借此保持确定性）
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('test: stream unavailable')))
  })

  afterEach(() => {
    localStorage.clear()
    vi.unstubAllGlobals()
  })

  it('visible 为 true 时应显示浮窗', () => {
    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    expect(wrapper.find('.agent-chat-window').exists()).toBe(true)
  })

  it('visible 为 false 时不应显示浮窗', () => {
    const wrapper = mount(AgentChat, {
      props: { visible: false },
      global: globalStubs
    })
    expect(wrapper.find('.agent-chat-window').exists()).toBe(false)
  })

  it('点击关闭按钮应 emit update:visible false', async () => {
    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    await wrapper.find('.close-btn').trigger('click')
    const updateEvents = wrapper.emitted('update:visible')
    expect(updateEvents).toBeTruthy()
    expect(updateEvents![0]).toEqual([false])
  })

  it('输入消息并点击发送应显示用户消息', async () => {
    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    const input = wrapper.find('.chat-input')
    await input.setValue('最近7天销量如何？')
    await wrapper.find('.send-btn').trigger('click')

    const userMessages = wrapper.findAll('.message.user .message-content')
    expect(userMessages.length).toBeGreaterThan(0)
    expect(userMessages[userMessages.length - 1].text()).toContain('最近7天销量')
  })

  it('后端不可用时降级到模拟回复应包含关键词', async () => {
    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    await wrapper.find('.chat-input').setValue('订单')
    await wrapper.find('.send-btn').trigger('click')

    // 发送后应显示 loading 占位（"正在思考..."）
    expect(wrapper.find('.typing').exists()).toBe(true)

    // 等待 fetch 失败后降级到 mock 回复
    await flushPromises()

    const assistantMessages = wrapper.findAll('.message.assistant .message-content')
    expect(assistantMessages.length).toBeGreaterThanOrEqual(2)
    const lastReply = assistantMessages[assistantMessages.length - 1].text()
    // generateMockReply 对"订单"关键词返回包含"162"或"订单"的回复
    expect(lastReply).toMatch(/订单|162/)
  })

  it('后端返回 Result JSON 时应展示 data 字段内容', async () => {
    // 组件经统一 request 实例调用 POST /ai/erp/agent（拦截器已拆包为 { code, message, data }）
    mockedPost.mockResolvedValue({ code: 200, message: 'success', data: '近 7 天订单共 162 单，销售额 $8,456。' })

    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    await wrapper.find('.chat-input').setValue('销量')
    await wrapper.find('.send-btn').trigger('click')

    await flushPromises()

    // 应携带 userId 参数并设置 60s 超时
    expect(mockedPost).toHaveBeenCalledWith(
      '/ai/erp/agent',
      { message: '销量' },
      expect.objectContaining({ timeout: 60000 })
    )
    const assistantMessages = wrapper.findAll('.message.assistant .message-content')
    expect(assistantMessages.length).toBeGreaterThanOrEqual(2)
    const lastReply = assistantMessages[assistantMessages.length - 1].text()
    // 应渲染后端 data 字段的原文，而非 mock 兜底
    expect(lastReply).toContain('162 单')
    expect(lastReply).toContain('$8,456')
  })

  it('SSE 工具时间线应渲染调用过程并展示最终回复', async () => {
    // 注：SSE 规范要求每帧以空行结束；缺空行时多事件会合并为一帧（与真 EventSource 行为一致）
    const fetchMock = mockFetchSse([
      'event: tool_call\ndata: {"name":"query_inventory"}\n\n',
      'event: tool_result\ndata: {"name":"query_inventory","ok":true}\n\n',
      'event: final\ndata: {"content":"FINAL-ANSWER"}\n\nevent: done\ndata: {}\n\n'
    ])
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    await wrapper.find('.chat-input').setValue('查库存')
    await wrapper.find('.send-btn').trigger('click')
    // 流式读取跨多个 microtask 轮次，用 waitFor 等事件消费完成
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    const [url, init] = fetchMock.mock.calls[0] as unknown as [
      string,
      { headers: Record<string, string> }
    ]
    expect(url).toContain('/ai/chat-stream')
    expect(url).toContain(encodeURIComponent('查库存'))
    // 鉴权头必须透传（EventSource 时代缺失导致网关 401）
    expect(init.headers.token).toBe('test-token')
    // SSE 路径不应调用 POST
    expect(mockedPost).not.toHaveBeenCalled()

    // 工具调用过程渲染为 chips，最终回复展示
    await vi.waitFor(() => expect(wrapper.text()).toContain('query_inventory'))
    await vi.waitFor(() => expect(wrapper.text()).toContain('FINAL-ANSWER'))
    // 完成后 loading 复位，发送按钮可用
    expect((wrapper.find('.send-btn').element as HTMLButtonElement).disabled).toBe(false)
  })

  it('SSE 服务端 error 事件且无内容时应回退 POST 一次', async () => {
    const fetchMock = mockFetchSse(['event: error\ndata: {"message":"boom"}\n\n'])
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    await wrapper.find('.chat-input').setValue('查库存')
    await wrapper.find('.send-btn').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(mockedPost).toHaveBeenCalledTimes(1)
    // POST 同样失败（默认 mock 拒绝）→ 降级 mock 回复
    expect(wrapper.text()).toMatch(/库存/)
  })

  it('SSE 建连失败且无内容时应回退 POST 一次', async () => {
    // beforeEach 默认 fetch 拒绝即建连失败场景
    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    await wrapper.find('.chat-input').setValue('查库存')
    await wrapper.find('.send-btn').trigger('click')
    await flushPromises()
    await flushPromises()

    expect(mockedPost).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toMatch(/库存/)
  })

  it('后端返回业务失败（code!=200）时应降级到模拟回复', async () => {
    mockedPost.mockResolvedValue({ code: 500, message: 'LLM 超时', data: null })

    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    await wrapper.find('.chat-input').setValue('库存')
    await wrapper.find('.send-btn').trigger('click')

    await flushPromises()

    const assistantMessages = wrapper.findAll('.message.assistant .message-content')
    const lastReply = assistantMessages[assistantMessages.length - 1].text()
    // 业务失败走 generateMockReply 兜底
    expect(lastReply).toMatch(/SKU|库存|补货/)
  })
})
