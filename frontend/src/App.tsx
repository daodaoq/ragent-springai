import { useEffect, useState } from 'react'

interface HealthStatus {
  app: string
  time: string
  mysql: string
  redis: string
}

function App() {
  const [health, setHealth] = useState<HealthStatus | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  const checkHealth = async () => {
    setLoading(true)
    setError('')
    try {
      const res = await fetch('/api/health')
      const data = await res.json()
      if (data.code === 200) setHealth(data.data)
      else setError(data.message || '未知错误')
    } catch {
      setError('无法连接后端服务，请确认后端已启动')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    checkHealth()
  }, [])

  return (
    <div className="min-h-screen bg-slate-50 flex items-center justify-center p-4">
      <div className="bg-white rounded-2xl shadow-md p-8 w-[420px]">
        <h1 className="text-xl font-bold text-slate-800 mb-1">人工智能实验室问答系统</h1>
        <p className="text-sm text-slate-500 mb-6">P0 骨架 · 前后端连通性检查</p>

        {loading && <p className="text-slate-400 text-sm">检测中...</p>}

        {error && <p className="text-red-500 text-sm mb-4">{error}</p>}

        {health && (
          <div className="space-y-2 text-sm">
            <StatusRow label="应用" value={health.app} />
            <StatusRow label="MySQL" value={health.mysql} />
            <StatusRow label="Redis" value={health.redis} />
            <StatusRow label="时间" value={health.time} />
          </div>
        )}

        <button
          onClick={checkHealth}
          className="mt-6 w-full bg-blue-600 text-white rounded-lg py-2 hover:bg-blue-700 transition"
        >
          重新检测
        </button>
      </div>
    </div>
  )
}

function StatusRow({ label, value }: { label: string; value: string }) {
  const up = value === 'UP' || value.startsWith('UP(')
  return (
    <div className="flex justify-between items-center border-b border-slate-100 pb-2">
      <span className="text-slate-600">{label}</span>
      <span className={up ? 'text-green-600 font-medium' : 'text-red-500 font-medium'}>
        {value}
      </span>
    </div>
  )
}

export default App
