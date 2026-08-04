export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** 雪花 ID 后端以字符串序列化（防 JS 精度丢失），前端全程按 string 处理 */
export interface UserInfo {
  id: string
  username: string
  nickname: string
  role: 'STUDENT' | 'TEACHER' | 'ADMIN'
  avatar: string | null
  bio: string | null
  createdAt: string
}

export interface Tag {
  id: string
  name: string
}

export interface AnswerVO {
  id: string
  questionId: string
  userId: string
  content: string
  accepted: boolean
  createdAt: string
  author: UserInfo
}

export interface QuestionVO {
  id: string
  title: string
  content: string
  userId: string
  status: 'OPEN' | 'RESOLVED'
  bestAnswerId: string | null
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
  userId: string
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

/** AI 回答反馈明细（管理端表格） */
export interface FeedbackRecord {
  id: string
  userId: string
  nickname: string | null
  conversationId: string | null
  question: string
  answer: string
  rating: 1 | -1
  createdAt: string
}
