import { describe, expect, it } from 'vitest'
import { parseSseChunk } from './sseStream'

/**
 * SSE 解析器的边界测试。
 *
 * <p>为什么值得为几十行代码写这么多用例：解析器是纯函数，但它面对的输入是
 * 「TCP 分片」这种不可控的东西。一旦分片边界猜错，表现是在真实网络抖动时
 * 偶发乱码或丢事件 —— 本地全绿、线上偶发，是最难查的一类 bug。
 * 这里把每个边界钉死，让「猜」变成「断言」。</p>
 */
describe('parseSseChunk', () => {
  describe('基本解析', () => {
    it('解析出完整事件的 id / event / data 三个字段', () => {
      const { messages, rest } = parseSseChunk('id: 7\nevent: progress\ndata: {"d":"生成中"}\n\n')

      expect(messages).toHaveLength(1)
      expect(messages[0]).toEqual({
        id: '7',
        event: 'progress',
        data: '{"d":"生成中"}',
      })
      expect(rest).toBe('')
    })

    it('一次解析多个事件', () => {
      const { messages } = parseSseChunk(
        'id: 1\nevent: message\ndata: a\n\nid: 2\nevent: message\ndata: b\n\n',
      )

      expect(messages.map((m) => m.data)).toEqual(['a', 'b'])
      expect(messages.map((m) => m.id)).toEqual(['1', '2'])
    })

    it('缺省 id 时为 null，缺省 event 时按规范回落为 message', () => {
      const { messages } = parseSseChunk('data: hello\n\n')

      expect(messages[0].id).toBeNull()
      expect(messages[0].event).toBe('message')
      expect(messages[0].data).toBe('hello')
    })
  })

  describe('跨 chunk 的半个事件（最关键的一条）', () => {
    it('半个事件不产出消息，且原文保留在 rest 里', () => {
      const { messages, rest } = parseSseChunk('data: {"d":"前')

      expect(messages).toEqual([])
      // rest 必须是原文，不能把 \r\n 归一化后的结果留下污染下次拼接
      expect(rest).toBe('data: {"d":"前')
    })

    it('拼接上剩余片段后解析出完整事件', () => {
      const first = parseSseChunk('data: {"d":"前')
      const second = parseSseChunk(first.rest + '半"}\n\n')

      expect(second.messages).toHaveLength(1)
      expect(second.messages[0].data).toBe('{"d":"前半"}')
      expect(second.rest).toBe('')
    })

    it('事件边界恰好落在分隔符中间时不丢事件', () => {
      // 模拟服务端写出 "data: a\n\n" 但 TCP 只送来了 "data: a\n"
      const first = parseSseChunk('data: a\n')
      expect(first.messages).toEqual([])
      expect(first.rest).toBe('data: a\n')

      // 第二次补齐空行，事件才成形
      const second = parseSseChunk(first.rest + '\n')
      expect(second.messages).toHaveLength(1)
      expect(second.messages[0].data).toBe('a')
    })

    it('逐字符送入也能完整还原（最坏情况的分片）', () => {
      const payload = 'id: 42\nevent: done\ndata: {"d":"完成"}\n\n'
      let buffer = ''
      const collected: string[] = []

      for (const char of payload) {
        buffer += char
        const { messages, rest } = parseSseChunk(buffer)
        buffer = rest
        for (const message of messages) {
          collected.push(message.data)
        }
      }

      expect(collected).toEqual(['{"d":"完成"}'])
      expect(buffer).toBe('')
    })
  })

  describe('换行符兼容', () => {
    it('\\r\\n\\r\\n 与 \\n\\n 等价', () => {
      const crlf = parseSseChunk('id: 1\r\nevent: message\r\ndata: x\r\n\r\n')
      const lf = parseSseChunk('id: 1\nevent: message\ndata: x\n\n')

      expect(crlf.messages).toEqual(lf.messages)
      expect(crlf.messages[0].data).toBe('x')
    })

    it('混合换行也能解析', () => {
      const { messages } = parseSseChunk('id: 1\r\ndata: x\n\n')

      expect(messages[0].id).toBe('1')
      expect(messages[0].data).toBe('x')
    })
  })

  describe('多行 data', () => {
    it('多个 data 字段按规范用 \\n 拼接', () => {
      const { messages } = parseSseChunk('data: 第一行\ndata: 第二行\ndata: 第三行\n\n')

      expect(messages[0].data).toBe('第一行\n第二行\n第三行')
    })

    it('data 值内部空格保留，仅去掉冒号后的第一个空格', () => {
      const { messages } = parseSseChunk('data:  两个空格开头\n\n')

      // 规范：冒号后只去掉一个空格
      expect(messages[0].data).toBe(' 两个空格开头')
    })

    it('data 后无空格时不做裁剪', () => {
      const { messages } = parseSseChunk('data:{"raw":true}\n\n')

      expect(messages[0].data).toBe('{"raw":true}')
    })

    it('data 为空值时得到空串', () => {
      const { messages } = parseSseChunk('data:\n\n')

      expect(messages[0].data).toBe('')
    })
  })

  describe('注释行（心跳）', () => {
    it('以冒号开头的行被忽略且不产出事件', () => {
      const { messages } = parseSseChunk(':heartbeat\n\n')

      // 这是一个合法的 SSE 块，但内容全是注释 → 不产出事件
      expect(messages).toHaveLength(0)
    })

    it('心跳夹在正常事件之间时不影响解析', () => {
      const { messages } = parseSseChunk(
        ':keep-alive\n\nid: 1\ndata: a\n\n:keep-alive\n\nid: 2\ndata: b\n\n',
      )

      expect(messages.map((m) => m.data)).toEqual(['a', 'b'])
    })

    it('注释行与非注释行混在同一事件里时只忽略注释行', () => {
      const { messages } = parseSseChunk(':注释\nid: 1\ndata: a\n\n')

      expect(messages).toHaveLength(1)
      expect(messages[0].id).toBe('1')
      expect(messages[0].data).toBe('a')
    })

    it('只带 id 或 event 的块是真事件，不能因 data 为空被误杀', () => {
      // 这条用来卡住「过滤注释块」的修法：不能简单改成「data 为空就丢弃」，
      // 否则纯 id/event 块这类合法事件会被吞掉
      const withId = parseSseChunk('id: 9\n\n')
      expect(withId.messages).toHaveLength(1)
      expect(withId.messages[0]).toEqual({ id: '9', event: 'message', data: '' })

      const withEvent = parseSseChunk('event: ping\n\n')
      expect(withEvent.messages).toHaveLength(1)
      expect(withEvent.messages[0]).toEqual({ id: null, event: 'ping', data: '' })
    })
  })

  describe('空输入与空白', () => {
    it('空字符串不产出任何事件', () => {
      expect(parseSseChunk('')).toEqual({ messages: [], rest: '' })
    })

    it('纯空白不产出事件', () => {
      expect(parseSseChunk('   \n\n  \n\n').messages).toEqual([])
    })

    it('单个换行不产出事件且不误判为事件', () => {
      const { messages } = parseSseChunk('\n')

      expect(messages).toEqual([])
    })
  })

  describe('字段顺序与未知字段', () => {
    it('字段顺序不影响解析结果', () => {
      const a = parseSseChunk('data: x\nid: 1\nevent: message\n\n')
      const b = parseSseChunk('event: message\nid: 1\ndata: x\n\n')

      expect(a.messages).toEqual(b.messages)
    })

    it('未知字段被忽略', () => {
      const { messages } = parseSseChunk('id: 1\nretry: 3000\ndata: x\n\n')

      expect(messages[0]).toEqual({ id: '1', event: 'message', data: 'x' })
    })

    it('带冒号的字段名按第一个冒号切分', () => {
      // data 值本身含冒号（JSON 里很常见）
      const { messages } = parseSseChunk('data: {"url":"http://x"}\n\n')

      expect(messages[0].data).toBe('{"url":"http://x"}')
    })
  })
})
