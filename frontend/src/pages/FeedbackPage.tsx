import { useEffect, useState } from 'react'
import { getFeedbackList } from '../api/feedback'
import { getFeedbackStats } from '../api/stats'
import Pagination from '../components/Pagination'
import { useAuthStore } from '../store/auth'
import { formatTime } from '../utils/format'
import type { FeedbackRecord, FeedbackStats } from '../types'

type Filter = 0 | 1 | -1

const PAGE_SIZES = [10, 20, 50]

/** AI 回答反馈管理表：默认聚焦「不准确」案例，便于管理员/教师排查回答质量问题 */
export default function FeedbackPage() {
  const { user } = useAuthStore()
  const [stats, setStats] = useState<FeedbackStats>()
  const [records, setRecords] = useState<FeedbackRecord[]>([])
  const [total, setTotal] = useState(0)
  const [rating, setRating] = useState<Filter>(-1)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [expanded, setExpanded] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const canView = !!user && (user.role === 'ADMIN' || user.role === 'TEACHER')

  const load = async (r: Filter, p: number, size: number) => {
    setLoading(true)
    setError('')
    try {
      const res = await getFeedbackList({ rating: r === 0 ? undefined : r, page: p, pageSize: size })
      setRecords(res.data.records)
      setTotal(res.data.total)
      setPage(p)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (!canView) return
    getFeedbackStats()
      .then((r) => setStats(r.data))
      .catch(() => {})
  }, [canView])

  // 切换过滤条件/每页条数时回到第 1 页重新加载
  useEffect(() => {
    if (canView) load(rating, 1, pageSize)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rating, pageSize, canView])

  if (!canView) {
    return <div className="text-slate-400 text-sm py-16 text-center">无权限访问，仅管理员/教师可见</div>
  }

  const tabs: { key: Filter; label: string }[] = [
    { key: -1, label: `👎 不准确 (${stats?.down ?? '…'})` },
    { key: 1, label: `👍 有帮助 (${stats?.up ?? '…'})` },
    { key: 0, label: `全部 (${stats?.total ?? '…'})` },
  ]
  const totalPages = Math.max(1, Math.ceil(total / pageSize))
  const toggleExpand = (id: string) => setExpanded((e) => ({ ...e, [id]: !e[id] }))

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-bold text-slate-800">🤖 AI 回答反馈</h1>
        <span className="text-sm text-slate-500">{total} 条记录</span>
      </div>

      <div className="grid grid-cols-3 gap-3">
        <div className="bg-white border border-slate-200 rounded-xl px-4 py-3">
          <div className="text-xs text-slate-400">📊 好评率</div>
          <div className="text-2xl font-bold text-slate-800 mt-1">{stats ? `${stats.upRate.toFixed(0)}%` : '—'}</div>
        </div>
        <div className="bg-white border border-slate-200 rounded-xl px-4 py-3">
          <div className="text-xs text-slate-400">👍 有帮助</div>
          <div className="text-2xl font-bold text-blue-600 mt-1">{stats?.up ?? '—'}</div>
        </div>
        <div className="bg-white border border-red-200 rounded-xl px-4 py-3">
          <div className="text-xs text-red-400">👎 不准确</div>
          <div className="text-2xl font-bold text-red-600 mt-1">{stats?.down ?? '—'}</div>
        </div>
      </div>

      <div className="flex gap-2">
        {tabs.map((t) => (
          <button
            key={t.key}
            onClick={() => setRating(t.key)}
            className={`px-4 py-1.5 rounded-lg text-sm border ${
              rating === t.key
                ? 'bg-blue-600 text-white border-blue-600'
                : 'bg-white text-slate-600 border-slate-300 hover:border-blue-300'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {error && <div className="text-red-500 text-sm">⚠️ {error}</div>}

      {loading && !records.length ? (
        <div className="text-slate-400 text-sm py-12 text-center">加载中...</div>
      ) : records.length === 0 ? (
        <div className="text-slate-400 text-sm py-12 text-center">
          {rating === -1 ? '暂无「不准确」反馈，AI 回答质量不错 🎉' : '暂无记录'}
        </div>
      ) : (
        <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs text-slate-400 border-b border-slate-100 bg-slate-50">
                <th className="px-4 py-3 font-medium">评价时间</th>
                <th className="px-4 py-3 font-medium">用户</th>
                <th className="px-4 py-3 font-medium w-1/4">问题</th>
                <th className="px-4 py-3 font-medium">AI 回答</th>
                <th className="px-4 py-3 font-medium">评价</th>
              </tr>
            </thead>
            <tbody>
              {records.map((r) => {
                const isDown = r.rating === -1
                const isExpanded = expanded[r.id]
                const needExpand = (r.answer ?? '').length > 120
                return (
                  <tr
                    key={r.id}
                    className={`border-b border-slate-50 align-top ${isDown ? 'bg-red-50/60' : 'bg-blue-50/40'}`}
                  >
                    <td className="px-4 py-3 text-xs text-slate-500 whitespace-nowrap">
                      {formatTime(r.createdAt)}
                    </td>
                    <td className="px-4 py-3 text-slate-600 whitespace-nowrap">
                      {r.nickname || (r.userId ? `用户 ${r.userId.slice(-6)}` : '匿名用户')}
                    </td>
                    <td className="px-4 py-3 text-slate-700">
                      <div className="line-clamp-3 whitespace-pre-wrap">{r.question}</div>
                    </td>
                    <td className="px-4 py-3 text-slate-600">
                      <div className={`whitespace-pre-wrap ${isExpanded ? '' : 'line-clamp-3'}`}>
                        {r.answer ?? ''}
                      </div>
                      {needExpand && (
                        <button
                          onClick={() => toggleExpand(r.id)}
                          className="text-xs text-blue-600 hover:underline mt-1"
                        >
                          {isExpanded ? '收起' : '展开完整回答'}
                        </button>
                      )}
                    </td>
                    <td className="px-4 py-3 whitespace-nowrap">
                      {isDown ? (
                        <span className="px-2 py-0.5 rounded-full text-xs bg-red-100 text-red-600 border border-red-200">
                          👎 不准确
                        </span>
                      ) : (
                        <span className="px-2 py-0.5 rounded-full text-xs bg-blue-100 text-blue-600 border border-blue-200">
                          👍 有帮助
                        </span>
                      )}
                      {r.traceId && (
                        <div className="mt-1 text-[10px] text-slate-400 font-mono max-w-[140px] truncate" title={r.traceId}>
                          traceId: {r.traceId}
                        </div>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}

      {totalPages > 1 && (
        <Pagination
          page={page}
          pageSize={pageSize}
          total={total}
          onPageChange={(p) => load(rating, p, pageSize)}
          onPageSizeChange={setPageSize}
          pageSizeOptions={PAGE_SIZES}
          className="pt-2"
        />
      )}
    </div>
  )
}
