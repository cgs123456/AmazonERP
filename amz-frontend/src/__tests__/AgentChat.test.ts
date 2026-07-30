import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AgentChat from '../components/AgentChat.vue'

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
    // 模拟后端 SSE 接口不可用，使组件降级到 generateMockReply
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('test: backend unavailable')))
  })

  afterEach(() => {
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

  it('SSE 流式回复应逐块追加到 assistant 消息', async () => {
    // 模拟 SSE 流式响应：分两块返回 data: 内容
    const encoder = new TextEncoder()
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode('data: {"content":"近 7 天"}\n\n'))
        controller.enqueue(encoder.encode('data: {"content":"订单 162 单"}\n\n'))
        controller.close()
      }
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      body: stream
    }))

    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    await wrapper.find('.chat-input').setValue('销量')
    await wrapper.find('.send-btn').trigger('click')

    await flushPromises()

    const assistantMessages = wrapper.findAll('.message.assistant .message-content')
    const lastReply = assistantMessages[assistantMessages.length - 1].text()
    // 两块 SSE 内容应被拼接
    expect(lastReply).toContain('近 7 天')
    expect(lastReply).toContain('订单 162 单')
  })
})
