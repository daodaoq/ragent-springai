interface PaginationProps {
  page: number
  pageSize: number
  total: number
  onPageChange: (page: number) => void
  /** 传了才显示「每页 N 条」选择器 */
  onPageSizeChange?: (pageSize: number) => void
  pageSizeOptions?: number[]
  /** 附加 className（例如表格底部横条的样式） */
  className?: string
}

/** 计算页码序列（7 个以内全显，多则首/尾 + 当前附近 + 省略号） */
function pageNumbers(page: number, totalPages: number): (number | '…')[] {
  if (totalPages <= 7) {
    return Array.from({ length: totalPages }, (_, i) => i + 1)
  }
  const nums: (number | '…')[] = [1]
  const start = Math.max(2, page - 1)
  const end = Math.min(totalPages - 1, page + 1)
  if (start > 2) nums.push('…')
  for (let i = start; i <= end; i++) nums.push(i)
  if (end < totalPages - 1) nums.push('…')
  nums.push(totalPages)
  return nums
}

/** 通用分页条：页码按钮（带省略号）+ 上/下一页 + 共 N 条 + 可选每页条数选择器 */
export default function Pagination({
  page,
  pageSize,
  total,
  onPageChange,
  onPageSizeChange,
  pageSizeOptions = [10, 20, 50, 100],
  className = '',
}: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize))
  const nums = pageNumbers(page, totalPages)

  return (
    <div
      className={`flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-sm text-slate-600 ${className}`}
    >
      <div className="flex items-center gap-3">
        <span className="text-slate-500">共 {total} 条</span>
        <span>
          第 {page} / {totalPages} 页
        </span>
        {onPageSizeChange && (
          <select
            className="border border-slate-300 rounded px-2 py-1 text-xs"
            value={pageSize}
            onChange={(e) => onPageSizeChange(Number(e.target.value))}
          >
            {pageSizeOptions.map((n) => (
              <option key={n} value={n}>
                每页 {n} 条
              </option>
            ))}
          </select>
        )}
      </div>
      <div className="flex items-center gap-1">
        <button
          disabled={page <= 1}
          onClick={() => onPageChange(page - 1)}
          className="px-2.5 py-1 rounded-lg bg-slate-100 disabled:opacity-40 hover:bg-slate-200"
        >
          ‹
        </button>
        {nums.map((n, i) =>
          n === '…' ? (
            <span key={`e${i}`} className="px-1 text-slate-400">
              …
            </span>
          ) : (
            <button
              key={n}
              onClick={() => onPageChange(n)}
              className={`px-2.5 py-1 rounded-lg text-xs ${
                n === page ? 'bg-blue-600 text-white' : 'bg-slate-100 hover:bg-slate-200'
              }`}
            >
              {n}
            </button>
          ),
        )}
        <button
          disabled={page >= totalPages}
          onClick={() => onPageChange(page + 1)}
          className="px-2.5 py-1 rounded-lg bg-slate-100 disabled:opacity-40 hover:bg-slate-200"
        >
          ›
        </button>
      </div>
    </div>
  )
}
