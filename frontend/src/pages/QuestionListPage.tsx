import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listQuestions } from '../api/question'
import { formatTime } from '../utils/format'
import type { QuestionVO } from '../types'

const PAGE_SIZE = 10

export default function QuestionListPage() {
  const [questions, setQuestions] = useState<QuestionVO[]>([])
  const [total, setTotal] = useState(0)
  const [keyword, setKeyword] = useState('')
  const [pageNum, setPageNum] = useState(1)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const load = async (page: number, kw: string) => {
    setLoading(true)
    setError('')
    try {
      const res = await listQuestions({ pageNum: page, pageSize: PAGE_SIZE, keyword: kw || undefined })
      setQuestions(res.data.records)
      setTotal(res.data.total)
      setPageNum(page)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load(1, '')
  }, [])

  const handleSearch = (e: React.FormEvent) => {
    e.preventDefault()
    load(1, keyword)
  }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <form onSubmit={handleSearch} className="flex-1 flex gap-2">
          <input
            className="flex-1 border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            placeholder="搜索问题标题或内容"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <button className="px-4 py-2 bg-slate-800 text-white rounded-lg text-sm hover:bg-slate-700">
            搜索
          </button>
        </form>
        <span className="text-sm text-slate-500">{total} 个问题</span>
      </div>

      {error && <div className="text-red-500 text-sm">{error}</div>}

      {loading && !questions.length ? (
        <div className="text-slate-400 text-sm">加载中...</div>
      ) : questions.length === 0 ? (
        <div className="text-slate-400 text-sm py-12 text-center">暂无问题，来提第一个问题吧</div>
      ) : (
        <div className="space-y-3">
          {questions.map((q) => (
            <Link
              key={q.id}
              to={`/questions/${q.id}`}
              className="block bg-white rounded-xl border border-slate-200 p-4 hover:border-blue-300 hover:shadow-sm transition"
            >
              <div className="flex items-start justify-between gap-3">
                <h2 className="font-medium text-slate-800 hover:text-blue-600">{q.title}</h2>
                {q.status === 'RESOLVED' ? (
                  <span className="shrink-0 px-2 py-0.5 rounded-full text-xs bg-green-50 text-green-600 border border-green-200">
                    已解决
                  </span>
                ) : (
                  <span className="shrink-0 px-2 py-0.5 rounded-full text-xs bg-amber-50 text-amber-600 border border-amber-200">
                    待解决
                  </span>
                )}
              </div>
              <p className="mt-1 text-sm text-slate-500 line-clamp-2">{q.content}</p>
              <div className="mt-3 flex items-center justify-between text-xs text-slate-400">
                <div className="flex items-center gap-2">
                  <span>{q.author.nickname}</span>
                  <span>·</span>
                  <span>{formatTime(q.createdAt)}</span>
                </div>
                <div className="flex items-center gap-2">
                  {q.tags.map((t) => (
                    <span key={t.id} className="px-2 py-0.5 rounded bg-slate-100 text-slate-500">
                      {t.name}
                    </span>
                  ))}
                  <span>{q.answerCount} 回答</span>
                  <span>{q.viewCount} 浏览</span>
                </div>
              </div>
            </Link>
          ))}
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 pt-2">
          <button
            disabled={pageNum <= 1}
            onClick={() => load(pageNum - 1, keyword)}
            className="px-3 py-1 rounded border border-slate-300 text-sm disabled:opacity-40"
          >
            上一页
          </button>
          <span className="text-sm text-slate-500">
            {pageNum} / {totalPages}
          </span>
          <button
            disabled={pageNum >= totalPages}
            onClick={() => load(pageNum + 1, keyword)}
            className="px-3 py-1 rounded border border-slate-300 text-sm disabled:opacity-40"
          >
            下一页
          </button>
        </div>
      )}
    </div>
  )
}
