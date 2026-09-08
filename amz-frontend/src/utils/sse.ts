// SSE 事件流解析器（fetch + ReadableStream 传输层共用）。
// EventSource 发不出自定义 header（token/shopId），网关鉴权下流式恒 401，
// 故改用 fetch（可透传请求头）+ 本解析器逐帧还原事件。

export type SseEventHandler = (event: string, data: string) => void

/**
 * 从字节流中按 SSE 帧协议逐个派发事件。
 * <p>
 * 语义对齐 EventSource：
 * <ul>
 *   <li>{@code :} 开头为注释/心跳行，直接丢弃；</li>
 *   <li>空行分帧；data 为空且无命名事件时忽略该帧；</li>
 *   <li>未命名事件统一为 {@code message}；多行 data 用 \n 拼接；</li>
 *   <li>chunk 边界可切在任意位置（含多字节字符中间），TextDecoder 流式解码保证不断裂。</li>
 * </ul>
 */
export async function readSseStream(
  body: ReadableStream<Uint8Array>,
  onEvent: SseEventHandler,
  signal?: AbortSignal
): Promise<void> {
  const reader = body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  let event = ''
  let dataLines: string[] = []

  const dispatch = () => {
    if (dataLines.length === 0) {
      // 无 data 的空帧（纯心跳/注释后的空行）：忽略
      event = ''
      return
    }
    onEvent(event || 'message', dataLines.join('\n'))
    event = ''
    dataLines = []
  }

  // 单行处理（行消费循环与尾部 flush 共用，避免两处逻辑漂移）
  const processLine = (rawLine: string) => {
    const line = rawLine.replace(/\r$/, '')
    if (line === '') {
      dispatch()
    } else if (!line.startsWith(':')) {
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      const val = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
      if (field === 'event') {
        // 容错：新事件行到达时若上一帧攒了 data 却无空行分隔（如末包粘连），
        // 先派发上一帧再开始新帧；规范流（空行已派发）走不到该分支，行为不变
        if (dataLines.length > 0) dispatch()
        event = val
      } else if (field === 'data') {
        dataLines.push(val)
      }
      // id/retry 等字段本业务用不到，忽略
    }
    // ':' 开头为注释/心跳：丢弃
  }

  try {
    for (;;) {
      if (signal?.aborted) {
        await reader.cancel().catch(() => undefined)
        return
      }
      const { done, value } = await reader.read()
      if (value && value.length > 0) {
        buf += decoder.decode(value, { stream: true })
      }
      if (done) break
      let idx: number
      // 逐行消费（保留行尾未完整的一段）
      while ((idx = buf.indexOf('\n')) >= 0) {
        processLine(buf.slice(0, idx))
        buf = buf.slice(idx + 1)
      }
    }
    // 流结束：残余可能含多行（末包多事件同达且无 trailing 空行），逐行过同一处理
    if (buf.length > 0) {
      for (const line of buf.split('\n')) processLine(line)
      buf = ''
    }
    dispatch()
  } finally {
    reader.releaseLock()
  }
}
