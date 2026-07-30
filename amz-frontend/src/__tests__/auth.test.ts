import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { InternalAxiosRequestConfig } from 'axios'
import request from '../api/auth'

// 自定义 axios adapter，用于在请求/响应拦截器测试中捕获配置或模拟响应
// 直接复用 axios 的 InternalAxiosRequestConfig，避免与 Record<string, unknown>
// 的索引签名不兼容导致 adapter 类型校验失败。
type AxiosConfig = InternalAxiosRequestConfig

function makeMockAdapter(responder: (config: AxiosConfig) => { data: unknown; status: number; statusText: string; headers: Record<string, string>; config: AxiosConfig }) {
  return vi.fn((config: AxiosConfig) => Promise.resolve(responder(config)))
}

describe('axios 拦截器', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('请求拦截器应添加 token header', async () => {
    localStorage.setItem('token', 'fake-token')
    const adapter = makeMockAdapter((config) => ({
      data: { code: 0 }, status: 200, statusText: 'OK', headers: {}, config
    }))
    await request.get('/test', { adapter })
    expect(adapter).toHaveBeenCalled()
    const captured = adapter.mock.calls[0][0] as AxiosConfig
    expect(captured.headers?.token).toBe('fake-token')
  })

  it('token 过期应清除并重定向', async () => {
    localStorage.setItem('token', 'expired-token')
    // 过期时间戳设为 1（远小于当前时间），确保拦截器判定为已过期
    localStorage.setItem('token_expiry', '1')

    // 拦截 window.location.href 赋值，避免 jsdom 真实导航
    const hrefSetter = vi.fn()
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { set href(v: string) { hrefSetter(v) }, get href() { return '/' } }
    })

    const adapter = makeMockAdapter((config) => ({
      data: {}, status: 200, statusText: 'OK', headers: {}, config
    }))

    // 拦截器检测到过期会 reject，不应到达 adapter
    await expect(request.get('/test', { adapter })).rejects.toThrow('Token 已过期')
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('token_expiry')).toBeNull()
    expect(hrefSetter).toHaveBeenCalledWith('/')
    expect(adapter).not.toHaveBeenCalled()
  })

  it('401 响应应清除 token', async () => {
    localStorage.setItem('token', 'will-be-401')

    // 拦截 window.location.href 赋值
    const hrefSetter = vi.fn()
    Object.defineProperty(window, 'location', {
      writable: true,
      value: { set href(v: string) { hrefSetter(v) }, get href() { return '/' } }
    })

    const adapter = vi.fn(() =>
      Promise.reject({
        response: { status: 401, data: 'Unauthorized' },
        config: {},
        message: 'Request failed with status code 401'
      })
    )

    await expect(request.get('/test', { adapter })).rejects.toThrow('未授权')
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('token_expiry')).toBeNull()
    expect(hrefSetter).toHaveBeenCalledWith('/')
  })
})