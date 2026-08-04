export interface RagSource {
  idx: number
  filename: string
  excerpt: string
  /** 检索相关度分数（P4 混合检索 + 重排后给出；纯向量降级时可能为空） */
  score?: number
}

interface StreamHandlers {
  onSources?: (sources: RagSource[]) => void
  onContent?: (text: string) => void
}

/**
 * 通用 SSE 流式读取（POST + fetch reader），支持命名事件（sources / content）
 */
async function streamSSE(url: string, body: unknown, handlers: StreamHandlers): Promise<void> {
  const token = localStorage.getItem('token')
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
  if (!res.ok || !res.body) {
    throw new Error('AI 服务不可用，请确认后端已启动')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let acc = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let idx: number
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const raw = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const lines = raw.split('\n')
      const event = lines.find((l) => l.startsWith('event:'))?.slice(6).trim() ?? 'content'
      const dataLine = lines.find((l) => l.startsWith('data:'))
      if (!dataLine) continue
      const payload = dataLine.slice(5).trim()
      if (!payload) continue
      if (event === 'sources') {
        try {
          handlers.onSources?.(JSON.parse(payload))
        } catch {
          // 忽略无法解析的来源
        }
      } else {
        acc += payload
        handlers.onContent?.(acc)
      }
    }
  }
}

/** 普通对话流式 */
export async function streamChat(message: string, onContent: (text: string) => void): Promise<void> {
  await streamSSE('/api/ai/chat/stream', { message }, { onContent })
}

/** RAG 知识库问答流式 */
export async function streamRag(message: string, handlers: StreamHandlers): Promise<void> {
  await streamSSE('/api/ai/rag/stream', { message }, handlers)
}
