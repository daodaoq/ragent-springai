import { useEffect, useState } from 'react'

/**
 * 客户端分页 hook：对全量数组做切片分页，返回当前页数据与分页控制。
 * 数据（items）变化时自动把越界页码收敛到最后一页。
 */
export function usePagination<T>(items: T[], defaultPageSize = 10) {
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(defaultPageSize)

  const total = items.length
  const totalPages = Math.max(1, Math.ceil(total / pageSize))

  useEffect(() => {
    if (page > totalPages) setPage(totalPages)
  }, [totalPages, page])

  const safePage = Math.min(page, totalPages)
  const paged = items.slice((safePage - 1) * pageSize, safePage * pageSize)

  return { page: safePage, pageSize, setPage, setPageSize, total, totalPages, paged }
}
