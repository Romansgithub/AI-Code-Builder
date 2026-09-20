/**
 * 生成进度流客户端（基于 fetch + ReadableStream 手写 SSE 解析）。
 *
 * <p><b>为什么不用原生 EventSource</b>：它有三个无法绕开的限制，每一个都在这个页面
 * 上踩过坑：</p>
 * <ol>
 *   <li><b>只支持 GET</b>：触发生成本来是写操作，挂在 GET 上就等于允许浏览器自动重放。
 *       网络一抖，EventSource 就带着同一个 URL 重连，后端老老实实再生成一遍
 *       —— 重复烧 token、重复写文件。</li>
 *   <li><b>不支持自定义请求头</b>：只能靠 cookie 认证，将来若改用 token 就得推倒重来；
 *       同时也没法主动带上 Last-Event-ID 做续传。</li>
 *   <li><b>自动重连不可控</b>：它自己决定什么时候重连、重连几次，页面完全插不上手，
 *       连「重连前先刷新会话」这种补救都做不到。</li>
 * </ol>
 *
 * <p>换成 fetch 之后这三件事都回到我们手里：请求方法、请求头、以及「什么时候重连」。
 * 代价是要自己解析 SSE 协议 —— 好在协议本身很简单，
 * 按空行切分事件、识别 {@code id:} / {@code event:} / {@code data:} 三个字段即可。</p>
 */

/** 一条解析后的 SSE 事件 */
export interface SseMessage {
  /** 服务端分配的事件序号，用于断点续传 */
  id: string | null
  /** 事件名，未指定时按 SSE 规范为 'message' */
  event: string
  /** 事件数据（多行 data 已按规范用换行拼接） */
  data: string
}

export interface SseStreamOptions {
  url: string
  /** 上一次成功收到的事件序号，用于续传；首次连接传 0 */
  lastEventId?: number
  signal: AbortSignal
  onMessage: (message: SseMessage) => void
  /** 网络层失败（非服务端主动下发的事件）时回调 */
  onTransportError?: (error: unknown) => void
}

/**
 * 解析一段 SSE 文本，返回事件列表与「未完成的尾巴」。
 *
 * <p>必须处理跨 chunk 的半个事件：TCP 不保证一次 read 就是一个完整事件，
 * 直接把每次 read 的内容丢掉解析，会在事件边界处产生随机乱码。
 * 因此这里把最后一个不完整块留到下次拼接。</p>
 */
export function parseSseChunk(buffer: string): { messages: SseMessage[]; rest: string } {
  const messages: SseMessage[] = []
  // SSE 用空行分隔事件；同时兼容 \r\n\r\n 与 \n\n
  const normalized = buffer.replace(/\r\n/g, '\n')
  const parts = normalized.split('\n\n')
  // 最后一段可能不完整，留到下次
  const rest = parts.pop() ?? ''

  for (const raw of parts) {
    if (!raw.trim()) continue
    let id: string | null = null
    let event = 'message'
    const dataLines: string[] = []
    // 这个块里是否出现过「有效字段行」。
    // 不能靠 dataLines.length 判断：只带 id/event 的块数据为空的，仍是真事件。
    // 用它来挡掉「整块全是注释（心跳）」的情况 —— 否则会推出一条 data 为空的
    // 幽灵事件，而它的 id 是 null，将来若被当成水位会污染续传起点。
    let hasField = false

    for (const line of raw.split('\n')) {
      // 以冒号开头的是注释行，包含整行就是冒号的空注释（心跳常用这种形式），直接忽略
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
      // 其余字段（retry 等）按规范忽略，也不计入 hasField
    }

    // 全是注释的块不产出事件
    if (!hasField) continue

    messages.push({ id, event, data: dataLines.join('\n') })
  }

  return { messages, rest }
}

/**
 * 建立一次 SSE 连接并消费到结束。
 *
 * <p>返回时代表这条连接已终止（正常结束或出错），调用方据此决定是否重连。
 * 这里刻意不做内置重试：「该不该重连」是业务判断 ——
 * 服务端已明确下发 done/error 就不该重连，而网络中断才值得重试。
 * 把控制权留给调用方，比在库内部猜要可靠。</p>
 */
export async function consumeSseStream(options: SseStreamOptions): Promise<void> {
  const { url, lastEventId = 0, signal, onMessage, onTransportError } = options

  const headers: Record<string, string> = {
    Accept: 'text/event-stream',
  }
  // 遵循 SSE 规范：用标准请求头告知续传起点，
  // 这样后端不必为「原生 EventSource」和「fetch 手写」维护两套逻辑
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
