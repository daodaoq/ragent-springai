import http from './http'
import type { ApiResult, FeedbackRecord, PageResult } from '../types'

/** 反馈明细分页（管理员/教师）；rating: 0/缺省=全部，1=有帮助，-1=不准确 */
export const getFeedbackList = (params: { rating?: number; page: number; pageSize: number }) =>
  http.get('/ai/feedback/list', { params }) as Promise<ApiResult<PageResult<FeedbackRecord>>>
