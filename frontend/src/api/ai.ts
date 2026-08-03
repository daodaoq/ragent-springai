/**
 * AI 流式对话：POST + SSE 解析（EventSource 不支持 POST，故用 fetch 读取流）
 * @param message 用户消息
 * @param onChunk 每个数据块的累积文本回调
 */
export async function streamChat(message: string, onChunk: (text: string) => void): Promise<void> {
  const token = localStorage.getItem('token')
  const res = await fetch('/api/ai/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ message }),
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

    // 按 SSE 帧边界 \n\n 切分，避免跨 chunk 截断
    let idx: number
    while ((idx = buffer.indexOf('\n\n')) !== -1) {
      const raw = buffer.slice(0, idx)
      buffer = buffer.slice(idx + 2)
      const dataLine = raw.split('\n').find((l) => l.startsWith('data:'))
      if (dataLine) {
        const payload = dataLine.slice(5).trim()
        if (payload) {
          acc += payload
          onChunk(acc)
        }
      }
    }
  }
}
