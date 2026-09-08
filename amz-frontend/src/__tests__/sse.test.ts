import { describe, it, expect } from 'vitest'
import { readSseStream, type SseEventHandler } from '@/utils/sse'

const streamOf = (chunks: Array<string | Uint8Array>) =>
  new ReadableStream<Uint8Array>({
    start(controller) {
      const enc = new TextEncoder()
      for (const c of chunks) controller.enqueue(typeof c === 'string' ? enc.encode(c) : c)
      controller.close()
    }
  })

const collect = async (body: ReadableStream<Uint8Array>) => {
  const events: Array<{ event: string; data: string }> = []
  const handler: SseEventHandler = (event, data) => events.push({ event, data })
  await readSseStream(body, handler)
  return events
}

describe('readSseStream（SSE 帧解析）', () => {
  it('命名事件逐帧派发', async () => {
    const events = await collect(
      streamOf(['event: tool_call\ndata: {"name":"a"}\n\nevent: done\ndata: {}\n\n'])
    )
    expect(events).toEqual([
      { event: 'tool_call', data: '{"name":"a"}' },
      { event: 'done', data: '{}' }
    ])
  })

  it('chunk 切在任意位置（含事件行中间）仍正确组帧', async () => {
    const events = await collect(streamOf(['event: to', 'ol_call\ndata: {"na', 'me":"a"}\n\n']))
    expect(events).toEqual([{ event: 'tool_call', data: '{"name":"a"}' }])
  })

  it('多字节字符被 chunk 切断不乱码', async () => {
    const bytes = new TextEncoder().encode('data: 查询成功\n\n')
    const events = await collect(streamOf([bytes.slice(0, 7), bytes.slice(7)]))
    expect(events).toEqual([{ event: 'message', data: '查询成功' }])
  })

  it('注释/心跳行丢弃，多行 data 用换行拼接', async () => {
    const events = await collect(
      streamOf([':ping\n\nevent: final\ndata: line1\ndata: line2\n\n'])
    )
    expect(events).toEqual([{ event: 'final', data: 'line1\nline2' }])
  })

  it('无 trailing 空行的尾帧仍派发', async () => {
    const events = await collect(streamOf(['event: final\ndata: tail']))
    expect(events).toEqual([{ event: 'final', data: 'tail' }])
  })

  it('纯注释流不派发任何事件', async () => {
    const events = await collect(streamOf([':hb\n\n:hb\n\n']))
    expect(events).toEqual([])
  })

  it('末包多事件同达且无 trailing 空行时逐帧派发（不合并丢帧）', async () => {
    const events = await collect(
      streamOf(['event: tool_result\ndata: {"ok":true}\nevent: final\ndata: {"content":"hi"}'])
    )
    expect(events).toEqual([
      { event: 'tool_result', data: '{"ok":true}' },
      { event: 'final', data: '{"content":"hi"}' }
    ])
  })
})
