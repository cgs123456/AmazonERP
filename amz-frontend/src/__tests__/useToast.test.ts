import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { useToast } from '../composables/useToast'

describe('useToast composable', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('showToast 应设置消息、类型并将 visible 置为 true', () => {
    const { toastVisible, toastMessage, toastType, showToast } = useToast()
    showToast('保存成功', 'success')
    expect(toastVisible.value).toBe(true)
    expect(toastMessage.value).toBe('保存成功')
    expect(toastType.value).toBe('success')
  })

  it('未指定类型时默认为 info', () => {
    const { toastType, showToast } = useToast()
    showToast('提示信息')
    expect(toastType.value).toBe('info')
  })

  it('error 类型应被正确设置', () => {
    const { toastType, showToast } = useToast()
    showToast('操作失败', 'error')
    expect(toastType.value).toBe('error')
  })

  it('duration 后 toastVisible 应自动变为 false（自动移除）', () => {
    const { toastVisible, showToast } = useToast()
    showToast('自动消失', 'info', 3000)
    expect(toastVisible.value).toBe(true)
    vi.advanceTimersByTime(3000)
    expect(toastVisible.value).toBe(false)
  })

  it('duration 默认为 3000ms', () => {
    const { toastVisible, showToast } = useToast()
    showToast('默认时长')
    expect(toastVisible.value).toBe(true)
    // 未到默认时长不应消失
    vi.advanceTimersByTime(2999)
    expect(toastVisible.value).toBe(true)
    // 到达 3000ms 后消失
    vi.advanceTimersByTime(1)
    expect(toastVisible.value).toBe(false)
  })

  it('再次调用 showToast 应取消前一次定时器（清除旧 timer）', () => {
    const { toastVisible, toastMessage, showToast } = useToast()
    showToast('第一次', 'info', 3000)
    // 推进 2000ms，未到第一次的 3000ms
    vi.advanceTimersByTime(2000)
    expect(toastVisible.value).toBe(true)

    // 再次调用应清除前一次定时器并重置 3000ms
    showToast('第二次', 'info', 3000)
    expect(toastVisible.value).toBe(true)
    expect(toastMessage.value).toBe('第二次')

    // 推进到第一次定时器本应触发的时间点，不应被隐藏
    vi.advanceTimersByTime(1000)
    expect(toastVisible.value).toBe(true)

    // 推进到第二次定时器触发点，才隐藏
    vi.advanceTimersByTime(2000)
    expect(toastVisible.value).toBe(false)
  })

  it('多次 useToast() 调用应共享同一份状态（单例）', () => {
    const a = useToast()
    const b = useToast()
    a.showToast('共享消息', 'error')
    expect(b.toastVisible.value).toBe(true)
    expect(b.toastMessage.value).toBe('共享消息')
    expect(b.toastType.value).toBe('error')
  })

  it('短 duration 的 toast 在 duration 后也应自动移除', () => {
    const { toastVisible, showToast } = useToast()
    showToast('快速提示', 'info', 500)
    vi.advanceTimersByTime(500)
    expect(toastVisible.value).toBe(false)
  })
})
