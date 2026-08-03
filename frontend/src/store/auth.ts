import { create } from 'zustand'
import { login as apiLogin, logout as apiLogout, register as apiRegister, fetchMe } from '../api/auth'
import type { LoginResult, UserInfo } from '../types'

const TOKEN_KEY = 'token'

interface AuthState {
  token: string | null
  user: UserInfo | null
  /** 应用启动时恢复登录态 */
  init: () => Promise<void>
  login: (username: string, password: string) => Promise<void>
  register: (data: { username: string; password: string; nickname: string; role?: string }) => Promise<void>
  logout: () => Promise<void>
}

export const useAuthStore = create<AuthState>((set) => ({
  token: localStorage.getItem(TOKEN_KEY),
  user: null,

  init: async () => {
    const token = localStorage.getItem(TOKEN_KEY)
    if (!token) return
    try {
      const res = await fetchMe()
      set({ user: res.data, token })
    } catch {
      // token 失效则清除
      localStorage.removeItem(TOKEN_KEY)
      set({ token: null, user: null })
    }
  },

  login: async (username, password) => {
    const res = await apiLogin({ username, password })
    const { token, user } = res.data as LoginResult
    localStorage.setItem(TOKEN_KEY, token)
    set({ token, user })
  },

  register: async (data) => {
    await apiRegister(data)
  },

  logout: async () => {
    try {
      await apiLogout()
    } catch {
      // 忽略服务端登出失败，本地照常清理
    }
    localStorage.removeItem(TOKEN_KEY)
    set({ token: null, user: null })
  },
}))
