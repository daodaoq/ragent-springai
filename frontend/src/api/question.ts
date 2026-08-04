import http from './http'
import type { ApiResult, PageResult, QuestionVO } from '../types'

export interface QuestionQuery {
  pageNum?: number
  pageSize?: number
  tagId?: string
  keyword?: string
}

export const listQuestions = (params: QuestionQuery) =>
  http.get('/questions/list', { params }) as Promise<ApiResult<PageResult<QuestionVO>>>

export const getQuestion = (id: string) =>
  http.get(`/questions/${id}`) as Promise<ApiResult<QuestionVO>>

export const createQuestion = (data: { title: string; content: string; tags?: string[] }) =>
  http.post('/questions', data) as Promise<ApiResult<QuestionVO>>

export const acceptAnswer = (questionId: string, answerId: string) =>
  http.post(`/questions/${questionId}/accept/${answerId}`) as Promise<ApiResult<null>>
