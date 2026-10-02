import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import Profile from '../views/Profile.vue'

vi.mock('@/api/auth', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn() },
  getUserInfo: vi.fn()
}))

vi.mock('@/api/profile', async () => {
  const actual = await vi.importActual<typeof import('@/api/profile')>('@/api/profile')
  return { ...actual, editUserInfo: vi.fn() }
})

import { getUserInfo } from '@/api/auth'
import type { UserVo } from '@/api/auth'
import { editUserInfo } from '@/api/profile'

const mockedGetUserInfo = vi.mocked(getUserInfo)
const mockedEdit = vi.mocked(editUserInfo)

const ok = <T>(data: T) => ({ code: 200, message: '操作成功', data } as any)
const fail = (message: string) => ({ code: 400, message, data: null } as any)

const USER: UserVo = {
  id: 12, phone: '13800001234', nickname: '张三', birthday: '1990-05-06',
  address: '杭州市西湖区', image: 'https://oss.example.com/a.png', sex: '1', role: 'OPERATOR'
}

const stubs = {
  stubs: {
    AppHeader: { template: '<div />' },
    AppSidebar: { template: '<div />' },
    Icon: true
  }
}

const mountPage = async () => {
  const wrapper = mount(Profile, { global: stubs })
  await flushPromises()
  return wrapper
}

const field = (wrapper: any, label: string) => {
  const f = wrapper.findAll('.field').filter((n: any) => n.text().includes(label))[0]
  expect(f, `找不到表单项「${label}」`).toBeTruthy()
  return f.find('input')
}

const clickBtn = async (wrapper: any, label: string) => {
  const matches = wrapper.findAll('button').filter((b: any) => b.text() === label)
  expect(matches.length, `按钮「${label}」不唯一（命中 ${matches.length} 个）`).toBe(1)
  await matches[0].trigger('click')
  await flushPromises()
}

describe('个人资料页', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    localStorage.clear()
    mockedGetUserInfo.mockResolvedValue(ok({ user: { ...USER }, age: 36 }) as never)
    mockedEdit.mockResolvedValue(ok(null) as never)
  })

  afterEach(() => {
    localStorage.clear()
  })

  it('只读区渲染账号信息，手机号打码且头像地址不外链', async () => {
    const wrapper = await mountPage()

    const cell = (label: string) => {
      const kv = wrapper.findAll('.kv').filter((n: any) => n.find('.k').text() === label)[0]
      expect(kv, `只读格里找不到「${label}」`).toBeTruthy()
      return kv.find('.v').text()
    }
    expect(cell('用户 ID')).toBe('12')
    expect(cell('手机号')).toBe('138****1234')
    expect(cell('角色')).toBe('OPERATOR')
    expect(cell('年龄')).toBe('36')
    expect(cell('性别编号')).toBe('1')
    expect(wrapper.find('.field-grid').text()).not.toContain('13800001234')
    // 头像按文本展示：不能出现 img 元素去请求第三方域名
    expect(wrapper.find('img').exists()).toBe(false)
    expect(cell('头像地址')).toContain('oss.example.com')
  })

  it('披露写清了不能改的字段与原因，而不是留三个没反应的输入框', async () => {
    const wrapper = await mountPage()
    const notice = wrapper.find('.notice-zone').text()

    expect(notice).toContain('手机号')
    expect(notice).toContain('your-bucket-name')
    expect(notice).toContain('已删列')
    expect(notice).toContain('TINYINT')
    // 不提供签名/学校/证件号/性别的输入框
    const labels = wrapper.findAll('.field').map((f) => f.text())
    expect(labels.some((t) => t.includes('签名') || t.includes('学校') || t.includes('证件') || t.includes('性别'))).toBe(false)
  })

  it('取不到用户时不发起写请求，也不渲染表单', async () => {
    mockedGetUserInfo.mockResolvedValue(ok({}) as never)

    const wrapper = await mountPage()

    expect(wrapper.find('.shop-tip').exists()).toBe(true)
    expect(wrapper.find('.form-card').exists()).toBe(false)
    expect(mockedEdit).not.toHaveBeenCalled()
  })

  it('回填当前值，没有改动时保存按钮禁用', async () => {
    const wrapper = await mountPage()

    expect((field(wrapper, '昵称').element as HTMLInputElement).value).toBe('张三')
    expect((field(wrapper, '地址').element as HTMLInputElement).value).toBe('杭州市西湖区')
    expect((wrapper.find('.form-actions .action-btn.primary').element as HTMLButtonElement).disabled).toBe(true)
    expect(wrapper.find('.form-actions').text()).toContain('没有改动')
  })

  it('只提交改过的字段', async () => {
    const wrapper = await mountPage()

    await field(wrapper, '昵称').setValue('张三丰')
    expect(wrapper.find('.form-actions').text()).toContain('将提交 1 个字段：昵称')
    await clickBtn(wrapper, '保存修改')

    expect(mockedEdit).toHaveBeenCalledTimes(1)
    expect(mockedEdit.mock.calls[0][0]).toEqual({ nickname: '张三丰' })
    expect(wrapper.find('.saved-tip').text()).toContain('已保存 1 个字段')
    // 保存后基准值更新，按钮重新禁用
    expect((wrapper.find('.form-actions .action-btn.primary').element as HTMLButtonElement).disabled).toBe(true)
  })

  it('清空昵称是显式提交空串，后端会真的清空', async () => {
    const wrapper = await mountPage()

    await field(wrapper, '昵称').setValue('   ')
    await clickBtn(wrapper, '保存修改')

    expect(mockedEdit.mock.calls[0][0]).toEqual({ nickname: '' })
  })

  it('放弃修改回到基准值，不发请求', async () => {
    const wrapper = await mountPage()

    await field(wrapper, '地址').setValue('上海市')
    await clickBtn(wrapper, '放弃修改')

    expect((field(wrapper, '地址').element as HTMLInputElement).value).toBe('杭州市西湖区')
    expect(mockedEdit).not.toHaveBeenCalled()
  })

  it('后端拒绝时展示原因，不假装已保存', async () => {
    mockedEdit.mockResolvedValue(fail('地址最长 200 字符，当前 201') as never)
    const wrapper = await mountPage()

    await field(wrapper, '地址').setValue('X'.repeat(201))
    await clickBtn(wrapper, '保存修改')

    expect(wrapper.find('.error-zone').text()).toContain('地址最长 200 字符')
    expect(wrapper.find('.saved-tip').exists()).toBe(false)
  })

  it('读账号信息失败时错误可见且可重试', async () => {
    mockedGetUserInfo.mockRejectedValueOnce(new Error('network down'))

    const wrapper = await mountPage()

    expect(wrapper.find('.error-zone').text()).toContain('network down')
    await clickBtn(wrapper, '重试')
    expect(mockedGetUserInfo).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.error-zone').exists()).toBe(false)
  })

  it('角色写进 localStorage 供其它页面做入口判断', async () => {
    await mountPage()
    expect(localStorage.getItem('user_role')).toBe('OPERATOR')
  })
})
