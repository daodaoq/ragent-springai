import { useEffect } from 'react'
import AppRouter from './router'
import { useAuthStore } from './store/auth'

export default function App() {
  const init = useAuthStore((s) => s.init)

  // 应用启动时从 localStorage 恢复登录态
  useEffect(() => {
    init()
  }, [init])

  return <AppRouter />
}
