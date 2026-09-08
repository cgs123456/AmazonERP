import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import axios from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import request, { extractLoginToken, refreshAccessToken } from '../api/auth'

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

  it('请求拦截器不再检查 token 过期，直接透传 token', async () => {
    localStorage.setItem('token', 'expired-token')
    // 即使 token_expiry 过期，拦截器也不再检查
    localStorage.setItem('token_expiry', '1')

    const adapter = makeMockAdapter((config) => ({
      data: { code: 200 }, status: 200, statusText: 'OK', headers: {}, config
    }))

    // 请求应正常通过，不再因 token 过期而 reject
    await expect(request.get('/test', { adapter })).resolves.toBeDefined()
    const captured = adapter.mock.calls[0][0] as AxiosConfig
    expect(captured.headers?.token).toBe('expired-token')
    expect(localStorage.getItem('token')).toBe('expired-token')
    expect(localStorage.getItem('token_expiry')).toBe('1')
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

  it('401 且有 refreshToken 时应静默刷新并重放原请求', async () => {
    localStorage.setItem('token', 'expired-token')
    localStorage.setItem('refreshToken', 'valid-refresh')
    const postSpy = vi.spyOn(axios, 'post').mockResolvedValue({
      data: { code: 200, message: 'ok', data: { token: 'new-token', refreshToken: 'new-refresh' } }
    } as never)

    let calls = 0
    const adapter = vi.fn((config: AxiosConfig) => {
      calls++
      if (calls === 1) {
        return Promise.reject({
          response: { status: 401, data: 'Unauthorized' },
          config,
          message: 'Request failed with status code 401'
        })
      }
      return Promise.resolve({
        data: { code: 200, message: 'ok', data: [] },
        status: 200, statusText: 'OK', headers: {}, config
      })
    })

    const res = await request.get('/test', { adapter }) as unknown as { code: number }
    expect(postSpy).toHaveBeenCalled()
    // 刷新请求应打到后端 /user/refresh（baseURL + 路径），refresh token 走 token 请求头
    const [url, , cfg] = postSpy.mock.calls[0] as unknown as [string, null, { headers: Record<string, string> }]
    expect(url).toContain('/user/refresh')
    expect(cfg.headers.token).toBe('valid-refresh')
    // 原请求被重放且成功，新 token 对已持久化
    expect(res.code).toBe(200)
    expect(calls).toBe(2)
    expect(localStorage.getItem('token')).toBe('new-token')
    expect(localStorage.getItem('refreshToken')).toBe('new-refresh')
    expect(localStorage.getItem('token_expiry')).not.toBeNull()
  })

  it('刷新失败时应清除凭证并拒绝（不再循环刷新）', async () => {
    localStorage.setItem('token', 'expired-token')
    localStorage.setItem('refreshToken', 'bad-refresh')
    const postSpy = vi.spyOn(axios, 'post').mockResolvedValue({
      data: { code: 400, message: 'refresh token 无效或已过期', data: null }
    } as never)

    const adapter = vi.fn((config: AxiosConfig) =>
      Promise.reject({
        response: { status: 401, data: 'Unauthorized' },
        config,
        message: 'Request failed with status code 401'
      })
    )

    await expect(request.get('/test', { adapter })).rejects.toThrow('未授权')
    expect(postSpy).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('refreshToken')).toBeNull()
  })

  it('/user/verify 自身 401 不应触发刷新', async () => {
    localStorage.setItem('token', 't')
    localStorage.setItem('refreshToken', 'r')
    const postSpy = vi.spyOn(axios, 'post')

    const adapter = vi.fn((config: AxiosConfig) =>
      Promise.reject({
        response: { status: 401, data: 'Unauthorized' },
        config: { ...config, url: '/user/verify' },
        message: 'Request failed with status code 401'
      })
    )

    await expect(request.get('/user/verify', { adapter })).rejects.toThrow('未授权')
    expect(postSpy).not.toHaveBeenCalled()
    expect(localStorage.getItem('token')).toBeNull()
  })

  it('无 refreshToken 时 refreshAccessToken 直接返回 false', async () => {
    await expect(refreshAccessToken()).resolves.toBe(false)
  })
})

describe('extractLoginToken（对齐后端 POST /user/verify 的 { token, refreshToken } 响应）', () => {
  it('对象形式应取出 token', () => {
    expect(extractLoginToken({ token: 'abc', refreshToken: 'ref' })).toBe('abc')
  })

  it('兼容历史裸字符串形式', () => {
    expect(extractLoginToken('plain-token')).toBe('plain-token')
  })

  it('缺失或空 token 应返回 null', () => {
    expect(extractLoginToken({})).toBeNull()
    expect(extractLoginToken({ token: '' })).toBeNull()
    expect(extractLoginToken(null)).toBeNull()
    expect(extractLoginToken(undefined)).toBeNull()
  })
})