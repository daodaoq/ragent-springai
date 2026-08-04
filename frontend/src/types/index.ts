export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export interface UserInfo {
  id: number
  username: string
  nickname: string
  role: 'STUDENT' | 'TEACHER' | 'ADMIN'
  avatar: string | null
  bio: string | null
  createdAt: string
}

export interface Tag {
  id: number
  name: string
}

export interface AnswerVO {
  id: number
  questionId: number
  userId: number
  content: string
  accepted: boolean
  createdAt: string
  author: UserInfo
}

export interface QuestionVO {
  id: number
  title: string
  content: string
  userId: number
  status: 'OPEN' | 'RESOLVED'
  bestAnswerId: number | null
  viewCount: number
  answerCount: number
  createdAt: string
  author: UserInfo
  tags: Tag[]
  answers: AnswerVO[]
}

export interface PageResult<T> {
  total: number
  pageNum: number
  pageSize: number
  records: T[]
}

export interface LoginResult {
  token: string
  user: UserInfo
}

/** Agent 工具调用信息（SSE tool-call 事件） */
export interface ToolCallInfo {
  name: string
  arguments?: string
  result?: string
}

/** 看板-总览 */
export interface StatsOverview {
  questions: number
  answers: number
  users: number
  tags: number
}

/** 看板-每日提问趋势 */
export interface TrendRow {
  createdDate: string
  cnt: number
}

/** 看板-标签分布 */
export interface TagCountRow {
  tagName: string
  cnt: number
}

/** 看板-Top 提问者 */
export interface TopAskerRow {
  userId: number
  nickname: string
  cnt: number
}

/** 看板-AI 回答反馈统计 */
export interface FeedbackStats {
  total: number
  up: number
  down: number
  upRate: number
}
