/**
 * 生成进度流客户端，基于 fetch + ReadableStream 手写 SSE 解析。
 *
 * 不用原生 EventSource：它只支持 GET，写操作会被浏览器自动重放导致重复生成，
 * 也无法自定义请求头与重连时机。改用 fetch 后这些控制权都回到调用方。
 */

export interface SseMessage {
  /** 事件序号，用于断点续传；服务端未下发时为 null */
  id: string | null
  /** 事件名，未指定时按 SSE 规范回落为 message */
  event: string
  /** 事件数据，多行 data 已按规范用换行拼接 */
  data: string
}

export interface SseStreamOptions {
  url: string
  /** 上一次成功收到的事件序号，用于续传；首次连接传 0 */
  lastEventId?: number
  signal: AbortSignal
  onMessage: (message: SseMessage) => void
  /** 仅网络层失败时回调，不含服务端下发的 error 事件 */
  onTransportError?: (error: unknown) => void
}

/**
 * 解析一段 SSE 文本，返回事件列表与未完成的尾巴。
 * 必须保留跨 chunk 的半个事件，否则事件边界处会出现随机乱码。
 */
export function parseSseChunk(buffer: string): { messages: SseMessage[]; rest: string } {
  const messages: SseMessage[] = []
  // 空行分隔事件，CRLF 与 LF 两种换行都要兼容
  const normalized = buffer.replace(/\r\n/g, '\n')
  const parts = normalized.split('\n\n')
  // 最后一段可能不完整，留到下次
  const rest = parts.pop() ?? ''

  for (const raw of parts) {
    if (!raw.trim()) continue
    let id: string | null = null
    let event = 'message'
    const dataLines: string[] = []
    // 不能用 dataLines.length 判断：只带 id/event 的块 data 为空但仍是真事件。
    // 靠它挡掉「整块全是注释（心跳）」，否则会推出 id 为 null 的幽灵事件污染续传水位。
    let hasField = false

    for (const line of raw.split('\n')) {
      // 冒号开头是注释行（心跳常用整行冒号），忽略
      if (line.startsWith(':')) continue
      const colon = line.indexOf(':')
      const field = colon === -1 ? line : line.slice(0, colon)
      // 规范规定冒号后若有一个空格需去掉
      let value = colon === -1 ? '' : line.slice(colon + 1)
      if (value.startsWith(' ')) value = value.slice(1)

      if (field === 'id') {
        id = value
        hasField = true
      } else if (field === 'event') {
        event = value
        hasField = true
      } else if (field === 'data') {
        dataLines.push(value)
        hasField = true
      }
      // retry 等其余字段按规范忽略，也不计入 hasField
    }

    if (!hasField) continue

    messages.push({ id, event, data: dataLines.join('\n') })
  }

  return { messages, rest }
}

/**
 * 建立一次 SSE 连接并消费到结束，返回即代表这条连接已终止。
 * 刻意不做内置重试：服务端已下发 done/error 就不该重连，网络中断才值得重试，
 * 是否重连交给调用方判断。
 */
export async function consumeSseStream(options: SseStreamOptions): Promise<void> {
  const { url, lastEventId = 0, signal, onMessage, onTransportError } = options

  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
  }
  // 用规范请求头告知续传起点，后端无需为原生 EventSource 与 fetch 维护两套逻辑
  if (lastEventId > 0) {
    headers['Last-Event-ID'] = String(lastEventId)
  }

  let response: Response
  try {
    response = await fetch(url, {
      method: 'GET',
      headers,
      credentials: 'include',
      signal,
    })
  } catch (error) {
    onTransportError?.(error)
    return
  }

  if (!response.ok) {
    onTransportError?.(new Error(`订阅进度失败：HTTP ${response.status}`))
    return
  }
  if (!response.body) {
    onTransportError?.(new Error('订阅进度失败：响应体为空'))
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      const { messages, rest } = parseSseChunk(buffer)
      buffer = rest
      for (const message of messages) {
        onMessage(message)
      }
    }
  } catch (error) {
    // 主动 abort 会走到这里，属于预期行为，不当成错误上报
    if (!signal.aborted) {
      onTransportError?.(error)
    }
  } finally {
    reader.releaseLock?.()
  }
}
