import http from './http'
import type { ApiResult, AnswerVO } from '../types'

export const createAnswer = (data: { questionId: string; content: string }) =>
  http.post('/answers', data) as Promise<ApiResult<AnswerVO>>
