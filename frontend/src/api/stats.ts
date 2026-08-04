import http from './http'
import type {
  ApiResult,
  FeedbackStats,
  StatsOverview,
  TagCountRow,
  TopAskerRow,
  TrendRow,
} from '../types'

export const getStatsOverview = () => http.get('/stats/overview') as Promise<ApiResult<StatsOverview>>

export const getQuestionTrend = (days = 14) =>
  http.get('/stats/question-trend', { params: { days } }) as Promise<ApiResult<TrendRow[]>>

export const getTagDistribution = () => http.get('/stats/tag-distribution') as Promise<ApiResult<TagCountRow[]>>

export const getTopAskers = (limit = 5) =>
  http.get('/stats/top-askers', { params: { limit } }) as Promise<ApiResult<TopAskerRow[]>>

export const getFeedbackStats = () => http.get('/ai/feedback/stats') as Promise<ApiResult<FeedbackStats>>
