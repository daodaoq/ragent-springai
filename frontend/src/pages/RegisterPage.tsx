import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/auth'

export default function RegisterPage() {
  const { register } = useAuthStore()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [nickname, setNickname] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (password !== confirm) {
      setError('两次输入的密码不一致')
      return
    }
    setError('')
    setLoading(true)
    try {
      await register({ username, password, nickname })
      navigate('/login')
    } catch (err) {
      setError(err instanceof Error ? err.message : '注册失败')
    } finally {
      setLoading(false)
    }
  }

  const inputCls =
    'w-full border border-slate-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500'

  return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center px-4">
      <form onSubmit={handleSubmit} className="bg-white rounded-2xl shadow-sm p-8 w-[400px]">
        <h1 className="text-xl font-bold text-slate-800 mb-1">注册</h1>
        <p className="text-sm text-slate-500 mb-6">🧪 加入人工智能实验室问答系统</p>
        {error && <div className="text-red-500 text-sm mb-4">{error}</div>}
        <div className="space-y-4">
          <input className={inputCls} placeholder="用户名（3-20位）" value={username}
            onChange={(e) => setUsername(e.target.value)} required minLength={3} maxLength={20} />
          <input className={inputCls} placeholder="昵称" value={nickname}
            onChange={(e) => setNickname(e.target.value)} required maxLength={20} />
          <input type="password" className={inputCls} placeholder="密码（至少6位）" value={password}
            onChange={(e) => setPassword(e.target.value)} required minLength={6} />
          <input type="password" className={inputCls} placeholder="确认密码" value={confirm}
            onChange={(e) => setConfirm(e.target.value)} required />
          <div className="text-xs text-slate-400">
            注册后默认为「学生」，教师/管理员身份由管理员在用户管理中授予
          </div>
          <button
            type="submit"
            disabled={loading}
            className="w-full bg-blue-600 text-white rounded-lg py-2 text-sm font-medium hover:bg-blue-700 disabled:opacity-60"
          >
            {loading ? '注册中...' : '注册'}
          </button>
        </div>
        <p className="text-sm text-slate-500 mt-5 text-center">
          已有账号？{' '}
          <Link to="/login" className="text-blue-600 hover:underline">
            去登录
          </Link>
        </p>
      </form>
    </div>
  )
}
