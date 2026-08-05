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
  /** 所属文档 ID（查看原文全文用；旧数据可能缺失） */
  documentId?: string
  /** 完整切片文本（点开来源查看用；旧数据可能缺失则退用 excerpt） */
  content?: string
}

/** 查询处理管线轨迹（P6 rewritten 事件：改写后的查询 + 各阶段运行情况） */
export interface RewrittenInfo {
  intent?: string
  rewrittenQuery?: string
  stages?: { name: string; ok: boolean; ms: number }[]
}

interface StreamHandlers {
  onSources?: (sources: RagSource[]) => void
  onContent?: (text: string) => void
  onToolCall?: (tool: ToolCallInfo) => void
  onRewritten?: (info: RewrittenInfo) => void
  /** 统一对话 mode 事件：rag / chat / agent */
  onMode?: (mode: string) => void
  /** 限流排队位次（1 基；仅排队时推送） */
  onQueuePosition?: (position: number) => void
  /** 限流被拒（队列满/排队超时） */
  onRateLimited?: (info: { reason?: string }) => void
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
      } else if (event === 'rewritten') {
        try {
          handlers.onRewritten?.(JSON.parse(payload))
        } catch {
          // 忽略无法解析的改写信息
        }
      } else if (event === 'mode') {
        try {
          handlers.onMode?.(JSON.parse(payload) as string)
        } catch {
          // 忽略无法解析的引擎标记
        }
      } else if (event === 'queue-position') {
        try {
          handlers.onQueuePosition?.(JSON.parse(payload) as number)
        } catch {
          // 忽略无法解析的位次
        }
      } else if (event === 'rate-limited') {
        try {
          handlers.onRateLimited?.(JSON.parse(payload))
        } catch {
          handlers.onRateLimited?.({})
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

/** RAG 知识库问答流式（P6 起带多轮记忆 conversationId） */
export async function streamRag(
  message: string,
  conversationId: string,
  handlers: StreamHandlers,
): Promise<void> {
  await streamSSE('/api/ai/rag/stream', { message, conversationId }, handlers)
}

/** Agent 智能体流式（tool-call 事件 + 最终 content；保留向后兼容） */
export async function streamAgent(
  message: string,
  conversationId: string,
  handlers: StreamHandlers,
): Promise<void> {
  await streamSSE('/api/ai/agent/stream', { message, conversationId }, handlers)
}

/** 统一对话流式（自动路由 RAG/Agent/普通对话）：先 mode 事件，再对应引擎事件；kbId=指定知识库（null=全部） */
export async function streamUnified(
  message: string,
  conversationId: string,
  handlers: StreamHandlers,
  kbId?: string | null,
): Promise<void> {
  await streamSSE('/api/ai/stream', { message, conversationId, ...(kbId ? { kbId } : {}) }, handlers)
}

/** 清空某会话的多轮记忆 */
export const clearMemory = (conversationId: string) =>
  http.post('/ai/memory/clear', { conversationId }) as Promise<ApiResult<null>>
