import { useCallback, useEffect, useRef, useState } from 'react'
import { listLogs } from '../api/logs'
import type { LogEntry } from '../api/logs'
import Pagination from '../components/Pagination'


const LEVEL_COLORS: Record<string, string> = {
  ERROR: 'text-red-600 bg-red-50',
  WARN: 'text-amber-600 bg-amber-50',
  INFO: 'text-blue-600 bg-blue-50',
  DEBUG: 'text-slate-500 bg-slate-100',
}

const LEVELS = ['', 'ERROR', 'WARN', 'INFO', 'DEBUG']
const MODULES = ['', 'controller', 'service', 'aspect', 'mapper', 'config']

export default function LogsPage() {
  const [records, setRecords] = useState<LogEntry[]>([])
  const [total, setTotal] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [pageNum, setPageNum] = useState(1)
  const [pageSize, setPageSize] = useState(20)
  const [levelFilter, setLevelFilter] = useState('')
  const [moduleFilter, setModuleFilter] = useState('')
  const [keyword, setKeyword] = useState('')
  const [loading, setLoading] = useState(false)
  const [msg, setMsg] = useState('')
  const [autoRefresh, setAutoRefresh] = useState(false)
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setMsg('')
    try {
      const res = await listLogs({
        pageNum,
        pageSize,
        level: levelFilter || undefined,
        module: moduleFilter || undefined,
        keyword: keyword || undefined,
      })
      setRecords(res.data.list)
      setTotal(res.data.total)
      setTotalPages(res.data.pages)
    } catch (err) {
      setMsg(err instanceof Error ? err.message : '查询日志失败')
    } finally {
      setLoading(false)
    }
  }, [pageNum, pageSize, levelFilter, moduleFilter, keyword])

  // 首次加载
  useEffect(() => {
    load()
  }, [load])

  // 自动刷新
  useEffect(() => {
    if (autoRefresh) {
      setPageNum(1)
      timerRef.current = setInterval(load, 5000)
    } else if (timerRef.current) {
      clearInterval(timerRef.current)
      timerRef.current = null
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current)
    }
  }, [autoRefresh])

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-slate-800">系统日志</h1>
          <p className="text-sm text-slate-500 mt-1">
            共 {total} 条日志
            {autoRefresh && <span className="ml-2 text-green-600">● 自动刷新中（5s）</span>}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-1.5 text-sm text-slate-600 cursor-pointer select-none">
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
              className="rounded"
            />
            自动刷新
          </label>
          <button
            onClick={load}
            className="text-sm px-3 py-1.5 rounded-lg bg-slate-100 hover:bg-slate-200 text-slate-700"
          >
            刷新
          </button>
        </div>
      </div>

      {msg && (
        <div className="text-sm text-blue-600 bg-blue-50 rounded-lg px-3 py-2">{msg}</div>
      )}

      {/* 筛选栏 */}
      <div className="bg-white rounded-2xl shadow-sm p-4 flex flex-wrap items-center gap-3">
        <select
          className="border border-slate-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          value={levelFilter}
          onChange={(e) => {
            setLevelFilter(e.target.value)
            setPageNum(1)
          }}
        >
          {LEVELS.map((l) => (
            <option key={l} value={l}>
              {l || '全部级别'}
            </option>
          ))}
        </select>
        <select
          className="border border-slate-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          value={moduleFilter}
          onChange={(e) => {
            setModuleFilter(e.target.value)
            setPageNum(1)
          }}
        >
          {MODULES.map((m) => (
            <option key={m} value={m}>
              {m || '全部模块'}
            </option>
          ))}
        </select>
        <input
          className="border border-slate-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 flex-1 min-w-[200px]"
          placeholder="搜索日志内容 / 用户ID / 操作..."
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value)
            setPageNum(1)
          }}
        />
      </div>

      {/* 日志表格 */}
      <div className="bg-white rounded-2xl shadow-sm overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm table-fixed">
            <thead className="bg-slate-50 text-slate-600">
              <tr>
                <th className="text-left px-4 py-3 font-medium w-[160px]">时间</th>
                <th className="text-left px-4 py-3 font-medium w-[64px]">级别</th>
                <th className="text-left px-4 py-3 font-medium w-[100px]">模块</th>
                <th className="text-left px-4 py-3 font-medium">消息</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {loading ? (
                <tr>
                  <td colSpan={4} className="text-center py-12 text-slate-400">
                    加载中...
                  </td>
                </tr>
              ) : records.length === 0 ? (
                <tr>
                  <td colSpan={4} className="text-center py-12 text-slate-400">
                    暂无日志（确认后端正在运行并已将日志发送至 Elasticsearch）
                  </td>
                </tr>
              ) : (
                records.map((entry) => {
                  const isExpanded = expandedId === entry.id
                  const msgLen = entry.message.length
                  const truncated = msgLen > 120 ? entry.message.slice(0, 120) + '...' : entry.message

                  return (
                    <tr
                      key={entry.id}
                      className={`hover:bg-slate-50 cursor-pointer transition-colors ${
                        entry.level === 'ERROR' ? 'bg-red-50/30' : ''
                      }`}
                      onClick={() => setExpandedId(isExpanded ? null : entry.id)}
                    >
                      <td className="px-4 py-2.5 text-xs text-slate-500 font-mono whitespace-nowrap">
                        {entry.timestamp}
                      </td>
                      <td className="px-4 py-2.5">
                        <span
                          className={`inline-block px-1.5 py-0.5 rounded text-xs font-medium ${LEVEL_COLORS[entry.level] || 'text-slate-500 bg-slate-100'}`}
                        >
                          {entry.level}
                        </span>
                      </td>
                      <td className="px-4 py-2.5 text-xs text-slate-500">{entry.module || '-'}</td>
                      <td className="px-4 py-2.5">
                        <div className="text-slate-700 break-all">
                          {isExpanded ? entry.message : truncated}
                        </div>
                        {isExpanded && (
                          <div className="mt-1.5 flex flex-wrap gap-3 text-xs text-slate-400">
                            {entry.userId && <span>用户: {entry.userId}</span>}
                            {entry.action && <span>操作: {entry.action}</span>}
                            {entry.logger && <span className="font-mono">{entry.logger}</span>}
                            {msgLen > 120 && (
                              <button
                                className="text-blue-500 hover:underline"
                                onClick={(e) => {
                                  e.stopPropagation()
                                  setExpandedId(null)
                                }}
                              >
                                收起
                              </button>
                            )}
                          </div>
                        )}
                      </td>
                    </tr>
                  )
                })
              )}
            </tbody>
          </table>
        </div>

        {/* 分页 */}
        <Pagination
          page={pageNum}
          pageSize={pageSize}
          total={total}
          onPageChange={setPageNum}
          onPageSizeChange={(s) => {
            setPageSize(s)
            setPageNum(1)
          }}
          className="border-t border-slate-100"
        />
      </div>
    </div>
  )
}
