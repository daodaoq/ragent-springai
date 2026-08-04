import { useEffect, useRef, useState } from 'react'
import { Link, Outlet, useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/auth'

const roleLabel: Record<string, string> = {
  STUDENT: '学生',
  TEACHER: '教师',
  ADMIN: '管理员',
}

/** 下拉菜单：触发按钮 + 绝对定位的菜单项列表；点击外部自动收起 */
function Dropdown({ label, items }: { label: string; items: { label: string; to: string }[] }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const onDown = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [])

  if (items.length === 0) return null

  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((o) => !o)}
        className={`flex items-center gap-1 hover:text-blue-600 ${open ? 'text-blue-600' : ''}`}
      >
        {label}
        <span className="text-[9px] leading-none">▾</span>
      </button>
      {open && (
        <div className="absolute left-0 mt-2 w-44 bg-white border border-slate-200 rounded-lg shadow-lg py-1 z-20">
          {items.map((it) => (
            <Link
              key={it.to}
              to={it.to}
              onClick={() => setOpen(false)}
              className="block px-3 py-2 text-sm text-slate-600 hover:bg-slate-50 hover:text-blue-600"
            >
              {it.label}
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}

export default function MainLayout() {
  const { user, logout } = useAuthStore()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await logout()
    navigate('/login')
  }

  const isStaff = user && (user.role === 'ADMIN' || user.role === 'TEACHER')
  const isAdmin = user && user.role === 'ADMIN'

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
              {user && (
                <Link to="/ask" className="hover:text-blue-600">
                  我要提问
                </Link>
              )}
              {user && (
                <Dropdown
                  label="知识库"
                  items={[
                    { label: '文档管理', to: '/kb' },
                    { label: '切片质量', to: '/kb/quality' },
                    { label: '检索管线', to: '/kb/pipeline' },
                  ]}
                />
              )}
              {user && (
                <Dropdown
                  label="管理"
                  items={[
                    { label: '数据看板', to: '/dashboard' },
                    ...(isStaff ? [{ label: 'AI 反馈', to: '/feedback' }] : []),
                    ...(isAdmin
                      ? [
                          { label: '用户管理', to: '/users' },
                          { label: '系统日志', to: '/logs' },
                        ]
                      : []),
                  ]}
                />
              )}
            </nav>
          </div>
          <div className="flex items-center gap-3 text-sm">
            {user ? (
              <>
                <Link to="/profile" className="text-slate-600 hover:text-blue-600">
                  {user.nickname}
                  <span className="ml-1 text-xs text-slate-400">({roleLabel[user.role]})</span>
                </Link>
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
