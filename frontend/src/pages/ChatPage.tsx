import { useEffect, useRef, useState } from 'react'
import { clearMemory, streamUnified, type RagSource, type RewrittenInfo } from '../api/ai'
import http from '../api/http'
import { getDocumentSource, type KbDocumentSource } from '../api/kb'
import Markdown from '../components/Markdown'
import type { ToolCallInfo } from '../types'

interface Message {
  role: 'user' | 'assistant'
  content: string
  sources?: RagSource[]
  tools?: ToolCallInfo[]
  /** P6 查询处理管线轨迹（改写后查询 + 各阶段） */
  rewritten?: RewrittenInfo
  /** 统一对话引擎标记（mode 事件）：rag / chat / agent */
  engine?: 'rag' | 'chat' | 'agent'
}

const ENGINE_LABEL: Record<NonNullable<Message['engine']>, string> = {
  rag: '📚 知识库检索',
  chat: '💬 对话',
  agent: '🤖 智能体工具',
}

const WELCOME =
  '你好！我是人工智能实验室的 AI 助手 👋\n\n我可以：\n- 📚 从实验室知识库检索资料回答你的问题（带引用来源）\n- 🤖 直接查询题库、统计提问、查标签，用真实数据回答\n- 💬 陪你随便聊聊\n\n直接问就行，我会自动判断用哪种方式回答你。'

const PLACEHOLDER = '输入你的问题，回车发送（Shift+Enter 换行），如：如何配置 DeepSeek？'

const CONV_KEY = 'conv'

/** 会话 ID（localStorage 持久化，仅前端记录，后端记忆按 ID 存取） */
function genId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return Date.now().toString(36) + Math.random().toString(36).slice(2)
}

function loadConvId(): string {
  let id = localStorage.getItem(CONV_KEY)
  if (!id) {
    id = genId()
    localStorage.setItem(CONV_KEY, id)
  }
  return id
}

export default function ChatPage() {
  const [convId, setConvId] = useState<string>(() => loadConvId())
  const [messages, setMessages] = useState<Message[]>([{ role: 'assistant', content: WELCOME }])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  /** 每条助手消息的赞/踩选中态（key = 消息下标） */
  const [feedback, setFeedback] = useState<Record<number, 1 | -1>>({})
  /** 反馈提交失败时的提示（3s 自动消失） */
  const [feedbackError, setFeedbackError] = useState('')
  /** 点开的来源详情弹窗（默认看完整切片，可切换「查看原文全文」） */
  const [sourceDetail, setSourceDetail] = useState<RagSource | null>(null)
  const [sourceView, setSourceView] = useState<'chunk' | 'full'>('chunk')
  const [sourceFull, setSourceFull] = useState<KbDocumentSource | null>(null)
  const [sourceFullLoading, setSourceFullLoading] = useState(false)
  const [sourceFullError, setSourceFullError] = useState('')
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const openSourceDetail = (s: RagSource) => {
    setSourceDetail(s)
    setSourceView('chunk')
    setSourceFull(null)
    setSourceFullError('')
  }

  /** 弹窗内加载该来源所属文档的原文全文 */
  const loadSourceFull = async (s: RagSource) => {
    if (!s.documentId) {
      setSourceFullError('该来源缺少文档 ID（旧数据），无法查看原文')
      setSourceView('full')
      return
    }
    setSourceView('full')
    setSourceFull(null)
    setSourceFullLoading(true)
    setSourceFullError('')
    try {
      const res = await getDocumentSource(s.documentId)
      setSourceFull(res.data)
    } catch (err) {
      setSourceFullError(err instanceof Error ? err.message : '加载原文失败')
    } finally {
      setSourceFullLoading(false)
    }
  }

  const clearConversation = () => {
    if (streaming) return
    clearMemory(convId).catch(() => {})
    const id = genId()
    localStorage.setItem(CONV_KEY, id)
    setConvId(id)
    setMessages([{ role: 'assistant', content: WELCOME }])
    setFeedback({})
  }

  const updateLast = (patch: Partial<Message> | ((prev: Message) => Partial<Message>)) => {
    setMessages((m) => {
      const copy = [...m]
      const last = copy[copy.length - 1]
      copy[copy.length - 1] = { ...last, ...(typeof patch === 'function' ? patch(last) : patch) }
      return copy
    })
  }

  const send = async () => {
    const text = input.trim()
    if (!text || streaming) return
    setMessages((m) => [...m, { role: 'user', content: text }, { role: 'assistant', content: '' }])
    setInput('')
    setStreaming(true)
    try {
      await streamUnified(text, convId, {
        onMode: (m) => updateLast({ engine: (m as Message['engine']) ?? undefined }),
        onSources: (sources) => updateLast({ sources }),
        onContent: (acc) => updateLast({ content: acc }),
        onRewritten: (info) => updateLast({ rewritten: info }),
        onToolCall: (t) => updateLast((prev) => ({ tools: [...(prev.tools ?? []), t] })),
      })
    } catch (e) {
      updateLast({ content: '⚠️ ' + (e instanceof Error ? e.message : '连接失败') })
    } finally {
      setStreaming(false)
    }
  }

  const submitFeedback = async (i: number, rating: 1 | -1) => {
    if (feedback[i] != null) return
    setFeedbackError('')
    const msg = messages[i]
    const question = messages[i - 1]
    setFeedback((f) => ({ ...f, [i]: rating }))
    try {
      await http.post('/ai/feedback', {
        conversationId: convId,
        question: question && question.role === 'user' ? question.content : '',
        answer: msg.content,
        rating,
      })
    } catch {
      // 提交失败回滚选中态，并明确提示（不再静默，避免看起来「点不动」）
      setFeedback((f) => {
        const copy = { ...f }
        delete copy[i]
        return copy
      })
      setFeedbackError('⚠️ 反馈提交失败，请稍后重试')
      window.setTimeout(() => setFeedbackError(''), 3000)
    }
  }

  return (
    <div className="flex flex-col h-[calc(100vh-120px)]">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-lg font-bold text-slate-800">🤖 AI 助手</h1>
        <div className="flex items-center gap-2">
          <button
            onClick={clearConversation}
            disabled={streaming}
            className="text-xs px-2.5 py-1.5 rounded-lg border border-slate-300 text-slate-500 hover:text-red-500 hover:border-red-300 disabled:opacity-50"
          >
            🗑️ 清空对话
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto space-y-4 pb-4">
        {messages.map((msg, i) =>
          msg.role === 'user' ? (
            <div key={i} className="flex justify-end">
              <div className="max-w-[80%] bg-blue-600 text-white rounded-2xl rounded-br-sm px-4 py-2.5 text-sm whitespace-pre-wrap">
                {msg.content}
              </div>
            </div>
          ) : (
            <div key={i} className="space-y-2">
              {msg.engine && msg.content && (
                <div className="ml-2 text-[11px] text-slate-400">{ENGINE_LABEL[msg.engine]}</div>
              )}
              <div className="flex justify-start">
                <div className="max-w-[85%] bg-white border border-slate-200 rounded-2xl rounded-bl-sm px-4 py-2.5">
                  {msg.content ? (
                    <Markdown>{msg.content}</Markdown>
                  ) : (
                    <span className="text-slate-400 text-sm">{streaming ? '思考中...' : ''}</span>
                  )}
                </div>
              </div>
              {msg.rewritten?.rewrittenQuery &&
                i > 0 &&
                messages[i - 1].role === 'user' &&
                msg.rewritten.rewrittenQuery !== messages[i - 1].content && (
                  <div
                    className="ml-2 text-[11px] text-slate-400"
                    title={
                      msg.rewritten.stages?.length
                        ? '管线轨迹：' +
                          msg.rewritten.stages
                            .map((s) => `${s.name}${s.ok ? `(${s.ms}ms)` : '✗'}`)
                            .join(' → ')
                        : undefined
                    }
                  >
                    🔍 已优化检索：<span className="text-slate-500">{msg.rewritten.rewrittenQuery}</span>
                  </div>
                )}
              {msg.tools && msg.tools.length > 0 && (
                <div className="ml-2 flex flex-wrap gap-1">
                  {msg.tools.map((t, j) => (
                    <span
                      key={j}
                      className="text-xs bg-blue-50 border border-blue-200 text-blue-700 rounded-full px-2 py-0.5"
                      title={t.result}
                    >
                      🔧 {t.name}
                    </span>
                  ))}
                </div>
              )}
              {msg.sources && msg.sources.length > 0 && (
                <div className="ml-2 space-y-1">
                  <div className="text-xs text-slate-400">📎 引用来源</div>
                  {msg.sources.map((s) => (
                    <div
                      key={s.idx}
                      onClick={() => openSourceDetail(s)}
                      title="点击查看完整内容"
                      className="max-w-[85%] bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 cursor-pointer hover:bg-slate-100 hover:border-blue-300 transition-colors"
                    >
                      <div className="text-xs font-medium text-blue-600">
                        [{s.idx}] {s.filename}
                        <span className="text-[11px] text-slate-400 ml-1 font-normal">查看全文 ›</span>
                        {s.lineStart != null && (
                          <span className="text-xs text-slate-400 ml-2 font-normal">
                            {s.lineEnd != null ? `第 ${s.lineStart + 1}-${s.lineEnd + 1} 行` : `第 ${s.lineStart + 1} 行`}
                            {s.page != null ? ` · 第 ${s.page} 页` : ''}
                          </span>
                        )}
                        {s.score != null && (
                          <span className="text-xs text-slate-400 ml-2 font-normal">
                            相关度 {s.score.toFixed(2)}
                          </span>
                        )}
                      </div>
                      {s.headingPath && (
                        <div className="text-[11px] text-slate-400 mt-0.5 truncate">📑 {s.headingPath}</div>
                      )}
                      <div className="text-xs text-slate-500 mt-0.5 line-clamp-2">{s.excerpt}</div>
                    </div>
                  ))}
                </div>
              )}
              {msg.content && i > 0 && messages[i - 1].role === 'user' && (
                <div className="ml-2 flex items-center gap-3 text-xs text-slate-400">
                  <button
                    onClick={() => submitFeedback(i, 1)}
                    disabled={feedback[i] != null}
                    className={`hover:text-blue-600 ${feedback[i] === 1 ? 'text-blue-600' : ''}`}
                  >
                    {feedback[i] === 1 ? '👍 已反馈' : '👍 有帮助'}
                  </button>
                  <button
                    onClick={() => submitFeedback(i, -1)}
                    disabled={feedback[i] != null}
                    className={`hover:text-red-500 ${feedback[i] === -1 ? 'text-red-500' : ''}`}
                  >
                    {feedback[i] === -1 ? '👎 已反馈' : '👎 不准确'}
                  </button>
                </div>
              )}
            </div>
          ),
        )}
        <div ref={scrollRef} />
      </div>

      {feedbackError && <div className="text-xs text-red-500 mb-1">{feedbackError}</div>}
      <div className="border-t border-slate-200 pt-3 flex gap-2">
        <textarea
          className="flex-1 border border-slate-300 rounded-xl px-3 py-2 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-blue-500"
          rows={2}
          placeholder={PLACEHOLDER}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault()
              send()
            }
          }}
        />
        <button
          onClick={send}
          disabled={streaming || !input.trim()}
          className="shrink-0 px-5 bg-blue-600 text-white rounded-xl text-sm font-medium hover:bg-blue-700 disabled:opacity-50"
        >
          {streaming ? '生成中' : '发送'}
        </button>
      </div>

      {sourceDetail && (
        <div
          className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
          onClick={() => setSourceDetail(null)}
        >
          <div
            className="bg-white rounded-2xl shadow-xl w-full max-w-3xl h-[80vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-start justify-between px-5 py-4 border-b border-slate-200">
              <div className="min-w-0">
                <h2 className="font-bold text-slate-800 truncate">
                  {sourceView === 'full'
                    ? `📄 原文：${sourceFull?.filename ?? sourceDetail.filename}`
                    : `📎 来源 ${sourceDetail.idx}：${sourceDetail.filename}`}
                </h2>
                <p className="text-xs text-slate-400 mt-0.5">
                  {sourceView === 'full' ? (
                    sourceFull ? `${sourceFull.lineCount} 行 · ${sourceFull.contentType ?? '未知类型'}` : ' '
                  ) : (
                    <>
                      {sourceDetail.headingPath && <span>📑 {sourceDetail.headingPath} · </span>}
                      {sourceDetail.lineStart != null && (
                        <span>
                          {sourceDetail.lineEnd != null
                            ? `第 ${sourceDetail.lineStart + 1}-${sourceDetail.lineEnd + 1} 行`
                            : `第 ${sourceDetail.lineStart + 1} 行`}
                        </span>
                      )}
                      {sourceDetail.page != null && <span> · 第 {sourceDetail.page} 页</span>}
                      {sourceDetail.score != null && <span> · 相关度 {sourceDetail.score.toFixed(2)}</span>}
                    </>
                  )}
                </p>
              </div>
              <button
                onClick={() => setSourceDetail(null)}
                className="shrink-0 text-slate-400 hover:text-slate-600 text-lg leading-none"
              >
                ✕
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-4">
              {sourceView === 'full' ? (
                sourceFullLoading ? (
                  <div className="text-slate-400 text-sm py-10 text-center">加载原文中...</div>
                ) : sourceFullError ? (
                  <div className="text-red-500 text-sm py-10 text-center">⚠️ {sourceFullError}</div>
                ) : (
                  <pre className="text-sm text-slate-700 whitespace-pre-wrap font-sans leading-relaxed">
                    {sourceFull?.text}
                  </pre>
                )
              ) : (
                <pre className="text-sm text-slate-700 whitespace-pre-wrap font-sans leading-relaxed">
                  {sourceDetail.content ?? sourceDetail.excerpt}
                </pre>
              )}
            </div>
            <div className="flex items-center justify-between px-5 py-3 border-t border-slate-200">
              {sourceView === 'full' ? (
                <button
                  onClick={() => setSourceView('chunk')}
                  className="px-3 py-1.5 rounded-lg text-slate-500 text-sm hover:bg-slate-50"
                >
                  ← 返回切片内容
                </button>
              ) : (
                <button
                  onClick={() => loadSourceFull(sourceDetail)}
                  disabled={sourceFullLoading}
                  className="px-3 py-1.5 rounded-lg bg-blue-600 text-white text-sm hover:bg-blue-700 disabled:opacity-50"
                >
                  📄 查看原文全文
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
