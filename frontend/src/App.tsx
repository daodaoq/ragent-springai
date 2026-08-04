import { useEffect } from 'react'
import AppRouter from './router'
import { useAuthStore } from './store/auth'

export default function App() {
  const init = useAuthStore((s) => s.init)
  const initialized = useAuthStore((s) => s.initialized)

  // 应用启动时从 localStorage 恢复登录态
  useEffect(() => {
    init()
  }, [init])

  // 登录态校验完成前先显示加载页，避免刷新后被瞬间踢到登录页
  if (!initialized) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-slate-50">
        <div className="text-slate-400 text-sm">加载中...</div>
      </div>
    )
  }

  return <AppRouter />
}
