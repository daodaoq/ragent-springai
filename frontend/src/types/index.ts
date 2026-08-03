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
