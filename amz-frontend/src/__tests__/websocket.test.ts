import { describe, it, expect, vi, afterEach } from 'vitest'
import WebSocketManager from '@/utils/websocket'

describe('WebSocketManager.dispose', () => {
  afterEach(() => {
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it('dispose 应移除全局监听器并清空订阅', () => {
    const removeDocSpy = vi.spyOn(document, 'removeEventListener')
    const removeWinSpy = vi.spyOn(window, 'removeEventListener')

    const manager = new WebSocketManager('ws://localhost:1/socket')
    const off = manager.onMessage(() => undefined)
    off() // 先验证订阅/取消订阅本身可用
    manager.onStatusChange(() => undefined)
    manager.dispose()

    expect(removeDocSpy).toHaveBeenCalledWith('visibilitychange', expect.any(Function))
    expect(removeWinSpy).toHaveBeenCalledWith('beforeunload', expect.any(Function))
    expect(manager.isConnected()).toBe(false)
  })

  it('dispose 后 connect 应直接返回不再建连', () => {
    const manager = new WebSocketManager('ws://localhost:1/socket')
    localStorage.setItem('token', 't')
    manager.dispose()
    // 无 token 也不抛错、不建连
    expect(() => manager.connect()).not.toThrow()
    expect(manager.isConnected()).toBe(false)
  })
})
