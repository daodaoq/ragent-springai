import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { getQuestion, acceptAnswer } from '../api/question'
import { createAnswer } from '../api/answer'
import { useAuthStore } from '../store/auth'
import Markdown from '../components/Markdown'
import MarkdownEditor from '../components/MarkdownEditor'
import { formatTime } from '../utils/format'
import type { QuestionVO } from '../types'

export default function QuestionDetailPage() {
  const { id } = useParams()
  // 雪花 ID 是字符串，不能 Number()（会丢精度导致详情 404）
  const qid = id ?? ''
  const { user } = useAuthStore()
  const [question, setQuestion] = useState<QuestionVO | null>(null)
  const [content, setContent] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [submitting, setSubmitting] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await getQuestion(qid)
      setQuestion(res.data)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [qid])

  useEffect(() => {
    load()
  }, [load])

  const isOwner = user != null && question != null && user.id === question.userId

  const handleSubmitAnswer = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!content.trim()) return
    setSubmitting(true)
    setError('')
    try {
      await createAnswer({ questionId: qid, content })
      setContent('')
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '提交失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleAccept = async (answerId: string) => {
    try {
      await acceptAnswer(qid, answerId)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '采纳失败')
    }
  }

  if (loading) return <div className="text-slate-400 text-sm">加载中...</div>
  if (error && !question) return <div className="text-red-500 text-sm">{error}</div>
  if (!question) return null

  return (
    <div className="space-y-6">
      <div>
        <div className="flex items-center gap-2 mb-2">
          {question.status === 'RESOLVED' ? (
            <span className="px-2 py-0.5 rounded-full text-xs bg-green-50 text-green-600 border border-green-200">已解决</span>
          ) : (
            <span className="px-2 py-0.5 rounded-full text-xs bg-amber-50 text-amber-600 border border-amber-200">待解决</span>
          )}
          <span className="text-xs text-slate-400">{question.viewCount} 浏览</span>
        </div>
        <h1 className="text-xl font-bold text-slate-800">{question.title}</h1>
        <div className="mt-1 text-sm text-slate-400">
          {question.author.nickname} 提问于 {formatTime(question.createdAt)}
        </div>
      </div>

      <div className="bg-white rounded-xl border border-slate-200 p-5">
        <Markdown>{question.content}</Markdown>
      </div>

      <div className="flex items-center gap-2">
        {question.tags.map((t) => (
          <span key={t.id} className="px-2 py-0.5 rounded bg-slate-100 text-xs text-slate-500">
            {t.name}
          </span>
        ))}
      </div>

      <section>
        <h2 className="text-base font-semibold text-slate-800 mb-3">
          {question.answers.length} 个回答
        </h2>
        <div className="space-y-4">
          {question.answers.length === 0 && (
            <div className="text-slate-400 text-sm py-6 text-center">还没有回答，来抢答吧</div>
          )}
          {question.answers.map((a) => (
            <div
              key={a.id}
              className={`rounded-xl border p-5 ${a.accepted ? 'border-green-300 bg-green-50/50' : 'border-slate-200 bg-white'}`}
            >
              <div className="flex items-center justify-between mb-2">
                <div className="text-sm text-slate-500">
                  <span className="font-medium text-slate-700">{a.author.nickname}</span>
                  <span className="ml-2 text-xs text-slate-400">{formatTime(a.createdAt)}</span>
                </div>
                <div className="flex items-center gap-2">
                  {a.accepted && (
                    <span className="px-2 py-0.5 rounded-full text-xs bg-green-100 text-green-700">✓ 已采纳</span>
                  )}
                  {isOwner && !a.accepted && (
                    <button
                      onClick={() => handleAccept(a.id)}
                      className="px-2 py-0.5 rounded text-xs border border-green-300 text-green-600 hover:bg-green-50"
                    >
                      采纳
                    </button>
                  )}
                </div>
              </div>
              <Markdown>{a.content}</Markdown>
            </div>
          ))}
        </div>
      </section>

      <section className="bg-white rounded-xl border border-slate-200 p-5">
        <h2 className="text-base font-semibold text-slate-800 mb-3">我来回答</h2>
        {error && <div className="text-red-500 text-sm mb-3">{error}</div>}
        {user ? (
          <form onSubmit={handleSubmitAnswer} className="space-y-3">
            <MarkdownEditor
              value={content}
              onChange={setContent}
              placeholder="支持 Markdown 语法..."
              minHeight={120}
            />
            <button
              type="submit"
              disabled={submitting}
              className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-60"
            >
              {submitting ? '提交中...' : '提交回答'}
            </button>
          </form>
        ) : (
          <p className="text-sm text-slate-500">
            <a href="/login" className="text-blue-600 hover:underline">
              登录
            </a>
            后再回答。
          </p>
        )}
      </section>
    </div>
  )
}
