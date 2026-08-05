import http from './http'
import type { ApiResult } from '../types'

export interface LogEntry {
  id: string
  timestamp: string
  level: string
  message: string
  logger: string
  thread: string
  module: string
  action: string
  userId: string
  traceId: string
}

export interface LogPage {
  total: number
  pages: number
  pageNum: number
  pageSize: number
  list: LogEntry[]
}

/** 分页查询日志 */
export const listLogs = (params: {
  pageNum?: number
  pageSize?: number
  level?: string
  module?: string
  keyword?: string
}) => http.get('/logs', { params }) as Promise<ApiResult<LogPage>>
