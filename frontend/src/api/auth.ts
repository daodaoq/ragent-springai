import http from './http'
import type { ApiResult, LoginResult, UserInfo } from '../types'

export interface RegisterData {
  username: string
  password: string
  nickname: string
  role?: string
}

export interface LoginData {
  username: string
  password: string
}

export const register = (data: RegisterData) =>
  http.post('/auth/register', data) as Promise<ApiResult<UserInfo>>

export const login = (data: LoginData) =>
  http.post('/auth/login', data) as Promise<ApiResult<LoginResult>>

export const fetchMe = () =>
  http.get('/auth/me') as Promise<ApiResult<UserInfo>>

export const logout = () =>
  http.post('/auth/logout') as Promise<ApiResult<null>>
