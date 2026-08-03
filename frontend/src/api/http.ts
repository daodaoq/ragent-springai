import axios from 'axios'

/**
 * 统一 axios 实例
 * baseURL 走 /api，开发环境由 Vite 代理到后端 8080
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

// 请求拦截器：附加 JWT（P1 接入 Sa-Token 后生效）
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：统一解包 Result，非 200 视为错误
http.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res && res.code !== 200) {
      return Promise.reject(new Error(res.message || '请求失败'))
    }
    return res
  },
  (error) => Promise.reject(error),
)

export default http
