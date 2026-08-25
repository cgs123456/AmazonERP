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
    // 登录守卫需要 token；未登录时组件直接提示而不发起请求
    localStorage.setItem('token', 'test-token')
    // 模拟后端接口不可用，使组件降级到 generateMockReply
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('test: backend unavailable')))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
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
    // 组件已切换为标准 JSON 契约（POST /ai/erp/agent → Result<String>），
    // 旧 SSE 流式测试已过时（此前因缺少 .json() 方法意外走了 mock 兜底路径）
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ code: 200, message: 'success', data: '近 7 天订单共 162 单，销售额 $8,456。' })
    }))

    const wrapper = mount(AgentChat, {
      props: { visible: true },
      global: globalStubs
    })
    await wrapper.find('.chat-input').setValue('销量')
    await wrapper.find('.send-btn').trigger('click')

    await flushPromises()

    const assistantMessages = wrapper.findAll('.message.assistant .message-content')
    expect(assistantMessages.length).toBeGreaterThanOrEqual(2)
    const lastReply = assistantMessages[assistantMessages.length - 1].text()
    // 应渲染后端 data 字段的原文，而非 mock 兜底
    expect(lastReply).toContain('162 单')
    expect(lastReply).toContain('$8,456')
  })

  it('后端返回业务失败（code!=200）时应降级到模拟回复', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ code: 500, message: 'LLM 超时', data: null })
    }))

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
