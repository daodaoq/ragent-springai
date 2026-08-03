import { useEffect, useRef, useState } from 'react'
import { streamChat } from '../api/ai'
import Markdown from '../components/Markdown'

interface Message {
  role: 'user' | 'assistant'
  content: string
}

const WELCOME =
  '你好！我是人工智能实验室的 AI 助手 👋\n\n可以帮你解答深度学习、机器学习、实验环境配置等问题。'

export default function ChatPage() {
  const [messages, setMessages] = useState<Message[]>([{ role: 'assistant', content: WELCOME }])
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    scrollRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const send = async () => {
    const text = input.trim()
    if (!text || streaming) return
    setMessages((m) => [...m, { role: 'user', content: text }, { role: 'assistant', content: '' }])
    setInput('')
    setStreaming(true)
    try {
      await streamChat(text, (acc) => {
        setMessages((m) => {
          const copy = [...m]
          copy[copy.length - 1] = { role: 'assistant', content: acc }
          return copy
        })
      })
    } catch (e) {
      setMessages((m) => {
        const copy = [...m]
        copy[copy.length - 1] = {
          role: 'assistant',
          content: '⚠️ ' + (e instanceof Error ? e.message : '连接失败'),
        }
        return copy
      })
    } finally {
      setStreaming(false)
    }
  }

  return (
    <div className="flex flex-col h-[calc(100vh-120px)]">
      <h1 className="text-lg font-bold text-slate-800 mb-4">🤖 AI 助手</h1>

      <div className="flex-1 overflow-y-auto space-y-4 pb-4">
        {messages.map((msg, i) =>
          msg.role === 'user' ? (
            <div key={i} className="flex justify-end">
              <div className="max-w-[80%] bg-blue-600 text-white rounded-2xl rounded-br-sm px-4 py-2.5 text-sm whitespace-pre-wrap">
                {msg.content}
              </div>
            </div>
          ) : (
            <div key={i} className="flex justify-start">
              <div className="max-w-[85%] bg-white border border-slate-200 rounded-2xl rounded-bl-sm px-4 py-2.5">
                {msg.content ? (
                  <Markdown>{msg.content}</Markdown>
                ) : (
                  <span className="text-slate-400 text-sm">
                    {streaming ? '思考中...' : ''}
                  </span>
                )}
              </div>
            </div>
          ),
        )}
        <div ref={scrollRef} />
      </div>

      <div className="border-t border-slate-200 pt-3 flex gap-2">
        <textarea
          className="flex-1 border border-slate-300 rounded-xl px-3 py-2 text-sm resize-none focus:outline-none focus:ring-2 focus:ring-blue-500"
          rows={2}
          placeholder="输入你的问题，回车发送（Shift+Enter 换行）"
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
