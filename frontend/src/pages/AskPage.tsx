import { useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { createQuestion } from '../api/question'
import { useAuthStore } from '../store/auth'

export default function AskPage() {
  const { user } = useAuthStore()
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [tags, setTags] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  if (!user) return <Navigate to="/login" replace />

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const tagList = tags
        .split(/[,，\s]+/)
        .map((s) => s.trim())
        .filter(Boolean)
      const res = await createQuestion({ title, content, tags: tagList })
      navigate(`/questions/${res.data.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : '发布失败')
    } finally {
      setLoading(false)
    }
  }

  const inputCls =
    'w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500'

  return (
    <div className="max-w-2xl mx-auto">
      <h1 className="text-xl font-bold text-slate-800 mb-1">发布问题</h1>
      <p className="text-sm text-slate-500 mb-6">描述清楚你的问题，方便老师和同学解答</p>
      {error && <div className="text-red-500 text-sm mb-4">{error}</div>}
      <form onSubmit={handleSubmit} className="space-y-4">
        <input
          className={inputCls}
          placeholder="标题（一句话概括问题）"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          required
          maxLength={200}
        />
        <textarea
          className={`${inputCls} min-h-[180px]`}
          placeholder="问题详情，支持 Markdown 语法..."
          value={content}
          onChange={(e) => setContent(e.target.value)}
          required
        />
        <input
          className={inputCls}
          placeholder="标签，用逗号分隔（如：SpringAI, DeepSeek）"
          value={tags}
          onChange={(e) => setTags(e.target.value)}
        />
        <button
          type="submit"
          disabled={loading}
          className="px-6 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-60"
        >
          {loading ? '发布中...' : '发布问题'}
        </button>
      </form>
    </div>
  )
}
