import { useEffect, useRef, useState } from 'react'
import { clearMemory, streamAgent, streamChat, streamRag, type RagSource } from '../api/ai'
import http from '../api/http'
import Markdown from '../components/Markdown'
import type { ToolCallInfo } from '../types'

type Mode = 'chat' | 'rag' | 'agent'

interface Message {
  role: 'user' | 'assistant'
  content: string
  sources?: RagSource[]
  tools?: ToolCallInfo[]
}

const WELCOME: Record<Mode, string> = {
  chat: '你好！我是人工智能实验室的 AI 助手 👋\n\n可以帮你解答深度学习、机器学习、实验环境配置等问题。',
  rag: '切换到「知识库问答」模式后，我会从实验室知识库检索资料并给出带引用来源的回答。',
  agent: '切换到「Agent 智能体」模式后，我可以直接查询题库、统计提问、查标签，用真实数据回答你。\n\n试试问我：**查一下题库里深度学习相关的提问**',
}

const PLACEHOLDER: Record<Mode, string> = {
  chat: '输入你的问题，回车发送（Shift+Enter 换行）',
  rag: '问一个知识库相关的问题，如：如何配置 DeepSeek？',
  agent: '试试：查一下题库里深度学习相关的提问',
}

const CONV_KEY_PREFIX = 'conv:'

/** 每模式独立的会话 ID（localStorage 持久化，仅前端记录，后端记忆按 ID 存取） */
function genId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return Date.now().toString(36) + Math.random().toString(36).slice(2)
}

function loadConvId(mode: Mode): string {
  const key = CONV_KEY_PREFIX + mode
  let id = localStorage.getItem(key)
  if (!id) {
    id = genId()
    localStorage.setItem(key, id)
  }
  return id
}

export default function ChatPage() {
  const [mode, setMode] = useState<Mode>('rag')
  const [convId, setConvId] = useState<string>(() => loadConvId('rag'))
  const [messages, setMessages] = useState<Message[]>([{ role: 'assistant', content: WELCOME[mode] }])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  /** 每条助手消息的赞/踩选中态（key = 消息下标） */
  const [feedback, setFeedback] = useState<Record<number, 1 | -1>>({})
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const switchMode = (m: Mode) => {
    if (m === mode || streaming) return
    setMode(m)
    setConvId(loadConvId(m))
    setMessages([{ role: 'assistant', content: WELCOME[m] }])
    setFeedback({})
  }

  const clearConversation = () => {
    if (streaming) return
    clearMemory(convId).catch(() => {})
    const id = genId()
    localStorage.setItem(CONV_KEY_PREFIX + mode, id)
    setConvId(id)
    setMessages([{ role: 'assistant', content: WELCOME[mode] }])
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
      if (mode === 'rag') {
        await streamRag(text, {
          onSources: (sources) => updateLast({ sources }),
          onContent: (acc) => updateLast({ content: acc }),
        })
      } else if (mode === 'agent') {
        await streamAgent(text, convId, {
          onToolCall: (t) => updateLast((prev) => ({ tools: [...(prev.tools ?? []), t] })),
          onContent: (acc) => updateLast({ content: acc }),
        })
      } else {
        await streamChat(text, convId, (acc) => updateLast({ content: acc }))
      }
    } catch (e) {
      updateLast({ content: '⚠️ ' + (e instanceof Error ? e.message : '连接失败') })
    } finally {
      setStreaming(false)
    }
  }

  const submitFeedback = (i: number, rating: 1 | -1) => {
    if (feedback[i] != null) return
    const msg = messages[i]
    const question = messages[i - 1]
    setFeedback((f) => ({ ...f, [i]: rating }))
    http
      .post('/ai/feedback', {
        conversationId: convId,
        question: question && question.role === 'user' ? question.content : '',
        answer: msg.content,
        rating,
      })
      .catch(() =>
        setFeedback((f) => {
          const copy = { ...f }
          delete copy[i]
          return copy
        }),
      )
  }

  const modeBtns: { key: Mode; label: string }[] = [
    { key: 'rag', label: '知识库问答' },
    { key: 'chat', label: '普通对话' },
    { key: 'agent', label: 'Agent 智能体' },
  ]

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
          <div className="flex rounded-lg border border-slate-300 overflow-hidden text-sm">
            {modeBtns.map(({ key, label }) => (
              <button
                key={key}
                onClick={() => switchMode(key)}
                className={`px-3 py-1.5 ${mode === key ? 'bg-blue-600 text-white' : 'text-slate-600 hover:bg-slate-100'}`}
              >
                {label}
              </button>
            ))}
          </div>
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
              <div className="flex justify-start">
                <div className="max-w-[85%] bg-white border border-slate-200 rounded-2xl rounded-bl-sm px-4 py-2.5">
                  {msg.content ? (
                    <Markdown>{msg.content}</Markdown>
                  ) : (
                    <span className="text-slate-400 text-sm">{streaming ? '思考中...' : ''}</span>
                  )}
                </div>
              </div>
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
                    <div key={s.idx} className="max-w-[85%] bg-slate-50 border border-slate-200 rounded-lg px-3 py-2">
                      <div className="text-xs font-medium text-blue-600">
                        [{s.idx}] {s.filename}
                        {s.score != null && (
                          <span className="text-xs text-slate-400 ml-2 font-normal">
                            相关度 {s.score.toFixed(2)}
                          </span>
                        )}
                      </div>
                      <div className="text-xs text-slate-500 mt-0.5 line-clamp-2">{s.excerpt}</div>
                    </div>
                  ))}
                </div>
              )}
              {msg.content && (
                <div className="ml-2 flex items-center gap-3 text-xs text-slate-400">
                  <button
                    onClick={() => submitFeedback(i, 1)}
                    disabled={feedback[i] != null}
                    className={`hover:text-blue-600 ${feedback[i] === 1 ? 'text-blue-600' : ''}`}
                  >
                    👍 有帮助
                  </button>
                  <button
                    onClick={() => submitFeedback(i, -1)}
                    disabled={feedback[i] != null}
                    className={`hover:text-red-500 ${feedback[i] === -1 ? 'text-red-500' : ''}`}
                  >
                    👎 不准确
                  </button>
                </div>
              )}
            </div>
          ),
        )}
        <div ref={scrollRef} />
      </div>

      <div className="border-t border-slate-200 pt-3 flex gap-2">
        <textarea
          className="flex-1 border border-slate-300 rounded-xl px-3 py-2 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-blue-500"
          rows={2}
          placeholder={PLACEHOLDER[mode]}
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
    </div>
  )
}
