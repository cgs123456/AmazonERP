import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AgentMemory from '../views/AgentMemory.vue'

// 记忆端点整体 mock：本页不连真实后端，只验证「拿到什么响应就怎么渲染」
vi.mock('@/api/agentMemory', async () => {
  const actual = await vi.importActual<Record<string, unknown>>('@/api/agentMemory')
  const out: Record<string, unknown> = {}
  Object.entries(actual).forEach(([k, v]) => {
    out[k] = typeof v === 'function' ? vi.fn() : v
  })
  return out
})

// 身份来自 GET /user/getInfo（复用登录侧的 getUserInfo，不新增造身份的地方）
vi.mock('@/api/auth', () => ({
  default: { get: vi.fn(), post: vi.fn() },
  getUserInfo: vi.fn()
}))

import * as api from '@/api/agentMemory'
import { getUserInfo } from '@/api/auth'

const mockedGetUserInfo = vi.mocked(getUserInfo)

const ok = <T>(data: T) => ({ code: 200, message: '操作成功', data } as any)
const fail = (message: string) => ({ code: 400, message, data: null } as any)

const PREF = {
  id: 12, userId: 42, nickname: 'Alice', preferredShopId: 7,
  preferredShopName: null, preferredCategory: '瑜伽用品', language: 'EN',
  lastActiveTime: '2026-10-01T10:00:00', createTime: '2026-09-01T10:00:00', updateTime: null
}

const HISTORY = [
  { id: 2, sessionId: 'sess-42', userId: 42, role: 'user', content: '最近7天销量如何？', createTime: '2026-10-01T09:00:00' },
  { id: 3, sessionId: 'sess-42', userId: 42, role: 'assistant', content: '近 7 天 12 单。', createTime: null }
]

const stubs = { stubs: { AppHeader: { template: '<div />' }, AppSidebar: { template: '<div />' }, Icon: true } }

const happy = () => {
  mockedGetUserInfo.mockResolvedValue(ok({ user: { id: 42, nickname: 'E2E' } }) as any)
  vi.mocked(api.getAgentPreference).mockResolvedValue(ok({ ...PREF }))
  vi.mocked(api.getAgentHistory).mockResolvedValue(ok(HISTORY))
}

const mountPage = async () => {
  const wrapper = mount(AgentMemory, { global: stubs })
  await flushPromises()
  return wrapper
}

const fieldInput = async (wrapper: any, label: string) => {
  // 表单字段用 .field，筛选器用 .filter（「条数」在筛选行里），两者都要能找到
  const field = wrapper.findAll('.field, .filter').filter((f: any) => f.text().includes(label))[0]
  expect(field, `找不到表单项「${label}」`).toBeTruthy()
  return field.find('input, select')
}

const clickBtn = async (wrapper: any, label: string) => {
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
  await flushPromises()
}

describe('助手记忆页', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    happy()
  })

  it('身份取自 /user/getInfo，并用它读取偏好与历史', async () => {
    const wrapper = await mountPage()

    expect(vi.mocked(api.getAgentPreference).mock.calls[0][0]).toBe(42)
    expect(vi.mocked(api.getAgentHistory).mock.calls[0][0]).toBe(42)
    // 会话键展示同一条：sess-{userId}
    expect(wrapper.text()).toContain('sess-42')
    expect(wrapper.find('.skeleton-zone').exists()).toBe(false)
  })

  it('身份缺失时不发起任何记忆请求，并说明原因', async () => {
    mockedGetUserInfo.mockResolvedValue(ok({ user: {} }) as any)

    const wrapper = await mountPage()

    expect(api.getAgentPreference).not.toHaveBeenCalled()
    expect(api.getAgentHistory).not.toHaveBeenCalled()
    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(wrapper.find('.form-card').exists()).toBe(false)
  })

  it('偏好回填到表单，null 字段展示为 - 而不是空串假绿', async () => {
    const wrapper = await mountPage()

    expect((await fieldInput(wrapper, '昵称')).element.value).toBe('Alice')
    expect((await fieldInput(wrapper, '偏好店铺 ID')).element.value).toBe('7')
    expect((await fieldInput(wrapper, '偏好品类')).element.value).toBe('瑜伽用品')
    expect((await fieldInput(wrapper, '回复语言')).element.value).toBe('EN')
    // updateTime 为 null：展示 '-'，不能渲染成空单元格让人以为已同步
    expect(wrapper.find('.meta-row').text()).toContain('记录更新 -')
  })

  it('保存只提交非空字段，其余交给后端按需更新', async () => {
    vi.mocked(api.saveAgentPreference).mockResolvedValue(ok({ ...PREF, nickname: 'Bob' }))
    const wrapper = await mountPage()

    await (await fieldInput(wrapper, '昵称')).setValue('Bob')
    await (await fieldInput(wrapper, '偏好品类')).setValue('   ')
    await clickBtn(wrapper, '保存偏好')

    expect(api.saveAgentPreference).toHaveBeenCalledTimes(1)
    expect(vi.mocked(api.saveAgentPreference).mock.calls[0][0]).toEqual({
      nickname: 'Bob',
      preferredShopId: 7
    })
    expect(wrapper.find('.form-actions').text()).toContain('已保存')
    expect((await fieldInput(wrapper, '昵称')).element.value).toBe('Bob')
  })

  it('店铺 ID 非法（0/负数/非数字）时不提交该字段', async () => {
    vi.mocked(api.saveAgentPreference).mockResolvedValue(ok({ ...PREF }))
    const wrapper = await mountPage()

    await (await fieldInput(wrapper, '偏好店铺 ID')).setValue('-3')
    await clickBtn(wrapper, '保存偏好')

    expect(vi.mocked(api.saveAgentPreference).mock.calls[0][0]).toEqual({
      nickname: 'Alice',
      preferredCategory: '瑜伽用品'
    })
  })

  it('语言切换成功后以后端返回为准', async () => {
    vi.mocked(api.switchAgentLanguage).mockResolvedValue(ok({ ...PREF, language: 'JA' }))
    const wrapper = await mountPage()

    await (await fieldInput(wrapper, '回复语言')).setValue('JA')
    await clickBtn(wrapper, '仅切换语言')

    expect(api.switchAgentLanguage).toHaveBeenCalledWith('JA')
    expect((await fieldInput(wrapper, '回复语言')).element.value).toBe('JA')
    expect(wrapper.find('.lang-row').text()).toContain('当前生效 JA')
  })

  it('后端拒绝非法语言代码时展示原因，不改本地状态', async () => {
    vi.mocked(api.switchAgentLanguage).mockResolvedValue(
      fail('language 仅支持 ZH/EN/JA/DE，实际收到「FR」')
    )
    const wrapper = await mountPage()

    await (await fieldInput(wrapper, '回复语言')).setValue('FR')
    await clickBtn(wrapper, '仅切换语言')

    expect(wrapper.find('.error-zone').text()).toContain('仅支持 ZH/EN/JA/DE')
    expect(wrapper.find('.lang-row').text()).toContain('当前生效 EN')
  })

  it('对话记忆按行渲染，缺失时间显示为未记录', async () => {
    const wrapper = await mountPage()

    const rows = wrapper.findAll('.history-card tbody tr')
    expect(rows.length).toBe(2)
    // 时间正序由后端保证，前端按原序渲染
    expect(rows[0].text()).toContain('2026-10-01T09:00:00')
    expect(rows[0].text()).toContain('最近7天销量如何？')
    expect(rows[1].text()).toContain('未记录')
    expect(rows[1].text()).toContain('assistant')
  })

  it('记忆为空时给出空态并说明 SSE 不落这张表', async () => {
    vi.mocked(api.getAgentHistory).mockResolvedValue(ok([]))

    const wrapper = await mountPage()

    expect(wrapper.find('.empty-state').text()).toContain('暂无对话记忆')
    expect(wrapper.find('.empty-state').text()).toContain('LangChain4j')
    expect(wrapper.find('.mock-badge').exists()).toBe(false)
  })

  it('切换条数按新的 limit 重新读取', async () => {
    const wrapper = await mountPage()

    await (await fieldInput(wrapper, '条数')).setValue('200')
    await flushPromises()

    expect(vi.mocked(api.getAgentHistory).mock.calls[1]).toEqual([42, 200])
  })

  it('偏好读取失败时展示错误而不是渲染空表单当成功', async () => {
    vi.mocked(api.getAgentPreference).mockResolvedValue(fail('用户偏好不存在或无权访问'))

    const wrapper = await mountPage()

    expect(wrapper.find('.error-zone').text()).toContain('无权访问')
    expect((await fieldInput(wrapper, '昵称')).element.value).toBe('')
  })

  it('请求抛异常时错误可见且可重试', async () => {
    vi.mocked(api.getAgentPreference).mockRejectedValueOnce(new Error('network down'))

    const wrapper = await mountPage()

    expect(wrapper.find('.error-zone').text()).toContain('network down')
    await clickBtn(wrapper, '重试')
    expect(api.getAgentPreference).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error-zone').exists()).toBe(false)
  })
})
