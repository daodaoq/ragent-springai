import { Link, Outlet, useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/auth'

const roleLabel: Record<string, string> = {
  STUDENT: '学生',
  TEACHER: '教师',
  ADMIN: '管理员',
}

export default function MainLayout() {
  const { user, logout } = useAuthStore()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <header className="bg-white border-b border-slate-200 sticky top-0 z-10">
        <div className="max-w-4xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-6">
            <Link to="/" className="font-bold text-slate-800">
              🧪 人工智能实验室问答
            </Link>
            <nav className="flex items-center gap-4 text-sm text-slate-600">
              <Link to="/" className="hover:text-blue-600">
                问题列表
              </Link>
              <Link to="/ai" className="hover:text-blue-600">
                AI 助手
              </Link>
              <Link to="/kb" className="hover:text-blue-600">
                知识库
              </Link>
              {user && (
                <Link to="/ask" className="hover:text-blue-600">
                  我要提问
                </Link>
              )}
            </nav>
          </div>
          <div className="flex items-center gap-3 text-sm">
            {user ? (
              <>
                <span className="text-slate-600">
                  {user.nickname}
                  <span className="ml-1 text-xs text-slate-400">({roleLabel[user.role]})</span>
                </span>
                <button onClick={handleLogout} className="text-slate-500 hover:text-red-500">
                  退出
                </button>
              </>
            ) : (
              <>
                <Link to="/login" className="text-slate-600 hover:text-blue-600">
                  登录
                </Link>
                <Link
                  to="/register"
                  className="px-3 py-1.5 rounded-lg bg-blue-600 text-white hover:bg-blue-700"
                >
                  注册
                </Link>
              </>
            )}
          </div>
        </div>
      </header>
      <main className="max-w-4xl mx-auto px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
