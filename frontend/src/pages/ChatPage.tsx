import { useEffect, useRef, useState } from 'react'
import { streamChat, streamRag, type RagSource } from '../api/ai'
import Markdown from '../components/Markdown'

type Mode = 'chat' | 'rag'

interface Message {
  role: 'user' | 'assistant'
  content: string
  sources?: RagSource[]
}

const WELCOME = {
  chat: '你好！我是人工智能实验室的 AI 助手 👋\n\n可以帮你解答深度学习、机器学习、实验环境配置等问题。',
  rag: '切换到「知识库问答」模式后，我会从实验室知识库检索资料并给出带引用来源的回答。',
}

export default function ChatPage() {
  const [mode, setMode] = useState<Mode>('rag')
  const [messages, setMessages] = useState<Message[]>([{ role: 'assistant', content: WELCOME[mode] }])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const switchMode = (m: Mode) => {
    if (m === mode || streaming) return
    setMode(m)
    setMessages([{ role: 'assistant', content: WELCOME[m] }])
  }

  const send = async () => {
    const text = input.trim()
    if (!text || streaming) return
    setMessages((m) => [...m, { role: 'user', content: text }, { role: 'assistant', content: '' }])
    setInput('')
    setStreaming(true)
    const updateLast = (patch: Partial<Message>) => {
      setMessages((m) => {
        const copy = [...m]
        copy[copy.length - 1] = { ...copy[copy.length - 1], ...patch }
        return copy
      })
    }
    try {
      if (mode === 'rag') {
        await streamRag(text, {
          onSources: (sources) => updateLast({ sources }),
          onContent: (acc) => updateLast({ content: acc }),
        })
      } else {
        await streamChat(text, (acc) => updateLast({ content: acc }))
      }
    } catch (e) {
      updateLast({ content: '⚠️ ' + (e instanceof Error ? e.message : '连接失败') })
    } finally {
      setStreaming(false)
    }
  }

  return (
    <div className="flex flex-col h-[calc(100vh-120px)]">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-lg font-bold text-slate-800">🤖 AI 助手</h1>
        <div className="flex rounded-lg border border-slate-300 overflow-hidden text-sm">
          <button
            onClick={() => switchMode('rag')}
            className={`px-3 py-1.5 ${mode === 'rag' ? 'bg-blue-600 text-white' : 'text-slate-600 hover:bg-slate-100'}`}
          >
            知识库问答
          </button>
          <button
            onClick={() => switchMode('chat')}
            className={`px-3 py-1.5 ${mode === 'chat' ? 'bg-blue-600 text-white' : 'text-slate-600 hover:bg-slate-100'}`}
          >
            普通对话
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
              <div className="flex justify-start">
                <div className="max-w-[85%] bg-white border border-slate-200 rounded-2xl rounded-bl-sm px-4 py-2.5">
                  {msg.content ? (
                    <Markdown>{msg.content}</Markdown>
                  ) : (
                    <span className="text-slate-400 text-sm">{streaming ? '思考中...' : ''}</span>
                  )}
                </div>
              </div>
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
            </div>
          ),
        )}
        <div ref={scrollRef} />
      </div>

      <div className="border-t border-slate-200 pt-3 flex gap-2">
        <textarea
          className="flex-1 border border-slate-300 rounded-xl px-3 py-2 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-blue-500"
          rows={2}
          placeholder={mode === 'rag' ? '问一个知识库相关的问题，如：如何配置 DeepSeek？' : '输入你的问题，回车发送（Shift+Enter 换行）'}
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
