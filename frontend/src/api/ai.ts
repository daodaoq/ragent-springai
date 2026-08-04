import http from './http'
import type { ApiResult, ToolCallInfo } from '../types'

export interface RagSource {
  idx: number
  filename: string
  excerpt: string
  /** 检索相关度分数（P4 混合检索 + 重排后给出；纯向量降级时可能为空） */
  score?: number
  /** 切片章节路径（如 "# 第一章 > ## 1.1"） */
  headingPath?: string
  /** 切片在原始文档中的行号范围（0 基；旧数据/纯向量降级时可能缺失） */
  lineStart?: number
  lineEnd?: number
  /** PDF 页码（1 基；非 PDF 缺失） */
  page?: number
}

interface StreamHandlers {
  onSources?: (sources: RagSource[]) => void
  onContent?: (text: string) => void
  onToolCall?: (tool: ToolCallInfo) => void
}

/**
 * 通用 SSE 流式读取（POST + fetch reader），支持命名事件（sources / tool-call / content）。
 * 一个事件块内可有多个 data: 行（非流式 content 事件含多行内容），按 SSE 规范以换行连接。
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
      const dataLines = lines.filter((l) => l.startsWith('data:'))
      if (dataLines.length === 0) continue
      const payload = dataLines.map((l) => l.slice(5)).join('\n')
      if (!payload) continue
      if (event === 'sources') {
        try {
          handlers.onSources?.(JSON.parse(payload))
        } catch {
          // 忽略无法解析的来源
        }
      } else if (event === 'tool-call') {
        try {
          handlers.onToolCall?.(JSON.parse(payload))
        } catch {
          // 忽略无法解析的工具调用
        }
      } else {
        acc += payload
        handlers.onContent?.(acc)
      }
    }
  }
}

/** 普通对话流式（P5 起带多轮记忆 conversationId） */
export async function streamChat(
  message: string,
  conversationId: string,
  onContent: (text: string) => void,
): Promise<void> {
  await streamSSE('/api/ai/chat/stream', { message, conversationId }, { onContent })
}

/** RAG 知识库问答流式（无状态） */
export async function streamRag(message: string, handlers: StreamHandlers): Promise<void> {
  await streamSSE('/api/ai/rag/stream', { message }, handlers)
}

/** Agent 智能体流式（tool-call 事件 + 最终 content） */
export async function streamAgent(
  message: string,
  conversationId: string,
  handlers: StreamHandlers,
): Promise<void> {
  await streamSSE('/api/ai/agent/stream', { message, conversationId }, handlers)
}

/** 清空某会话的多轮记忆 */
export const clearMemory = (conversationId: string) =>
  http.post('/ai/memory/clear', { conversationId }) as Promise<ApiResult<null>>
