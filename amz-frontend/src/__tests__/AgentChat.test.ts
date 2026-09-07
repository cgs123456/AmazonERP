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

// SSE EventSource 测试替身：记录实例与监听器，支持主动触发命名事件
class FakeEventSource {
  url: string
  closed = false
  private listeners: Record<string, Array<(ev: unknown) => void>> = {}

  constructor(url: string) {
    this.url = url
  }

  addEventListener(type: string, cb: (ev: unknown) => void) {
    if (!this.listeners[type]) this.listeners[type] = []
    this.listeners[type].push(cb)
  }

  removeEventListener() {
    // 测试替身无需实现
  }

  close() {
    this.closed = true
  }

  emit(type: string, data?: unknown) {
    const ev = data === undefined ? {} : { data: typeof data === 'string' ? data : JSON.stringify(data) }
    for (const cb of this.listeners[type] || []) cb(ev)
  }
}

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
  })

  afterEach(() => {
    localStorage.clear()
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
    const instances: FakeEventSource[] = []
    vi.stubGlobal('EventSource', class extends FakeEventSource {
      constructor(url: string) {
        super(url)
        instances.push(this)
      }
    })

    try {
      const wrapper = mount(AgentChat, {
        props: { visible: true },
        global: globalStubs
      })
      await wrapper.find('.chat-input').setValue('查库存')
      await wrapper.find('.send-btn').trigger('click')
      await flushPromises()

      expect(instances.length).toBe(1)
      expect(instances[0].url).toContain('/api/ai/chat-stream')
      expect(instances[0].url).toContain(encodeURIComponent('查库存'))
      // SSE 路径不应调用 POST
      expect(mockedPost).not.toHaveBeenCalled()

      // 工具调用过程渲染为 chips
      instances[0].emit('tool_call', { name: 'query_inventory' })
      await flushPromises()
      expect(wrapper.text()).toContain('query_inventory')

      instances[0].emit('tool_result', { name: 'query_inventory', ok: true })
      instances[0].emit('final', { content: 'FINAL-ANSWER' })
      instances[0].emit('done', {})
      await flushPromises()

      expect(wrapper.text()).toContain('FINAL-ANSWER')
      // 完成后 loading 复位，发送按钮可用
      expect((wrapper.find('.send-btn').element as HTMLButtonElement).disabled).toBe(false)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('SSE 连接失败且无内容时应回退 POST 一次', async () => {
    const instances: FakeEventSource[] = []
    vi.stubGlobal('EventSource', class extends FakeEventSource {
      constructor(url: string) {
        super(url)
        instances.push(this)
      }
    })

    try {
      const wrapper = mount(AgentChat, {
        props: { visible: true },
        global: globalStubs
      })
      await wrapper.find('.chat-input').setValue('查库存')
      await wrapper.find('.send-btn').trigger('click')
      await flushPromises()

      // 连接级错误（无 data）触发回退
      instances[0].emit('error')
      await flushPromises()

      expect(mockedPost).toHaveBeenCalledTimes(1)
      // POST 同样失败（默认 mock 拒绝）→ 降级 mock 回复
      expect(wrapper.text()).toMatch(/库存/)
    } finally {
      vi.unstubAllGlobals()
    }
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
