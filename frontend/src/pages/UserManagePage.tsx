import { useCallback, useEffect, useState } from 'react'
import { listUsers, updateUserRole, deleteUser } from '../api/user'
import type { UserInfo } from '../types'

const roleLabel: Record<string, string> = {
  STUDENT: '学生',
  TEACHER: '教师',
  ADMIN: '管理员',
}
const ROLES = ['STUDENT', 'TEACHER', 'ADMIN']

export default function UserManagePage() {
  const [records, setRecords] = useState<UserInfo[]>([])
  const [total, setTotal] = useState(0)
  const [pageNum, setPageNum] = useState(1)
  const [pageSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [roleFilter, setRoleFilter] = useState('')
  const [loading, setLoading] = useState(false)
  const [msg, setMsg] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    setMsg('')
    try {
      const res = await listUsers({ pageNum, pageSize, keyword: keyword || undefined, role: roleFilter || undefined })
      setRecords(res.data.records)
      setTotal(res.data.total)
    } catch (err) {
      setMsg(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }, [pageNum, pageSize, keyword, roleFilter])

  useEffect(() => {
    load()
  }, [load])

  const handleRoleChange = async (id: string, role: string) => {
    setMsg('')
    try {
      await updateUserRole(id, role)
      setRecords((prev) => prev.map((u) => (u.id === id ? { ...u, role: role as UserInfo['role'] } : u)))
      setMsg('角色已更新')
    } catch (err) {
      setMsg(err instanceof Error ? err.message : '更新失败')
    }
  }

  const handleDelete = async (id: string, username: string) => {
    if (!confirm(`确认删除用户「${username}」？此操作不可恢复。`)) return
    setMsg('')
    try {
      await deleteUser(id)
      setMsg('用户已删除')
      // 删除后若当前页空了且不是第一页，回退一页
      if (records.length === 1 && pageNum > 1) {
        setPageNum((p) => p - 1)
      } else {
        load()
      }
    } catch (err) {
      setMsg(err instanceof Error ? err.message : '删除失败')
    }
  }

  const totalPages = Math.ceil(total / pageSize) || 1

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-bold text-slate-800">用户管理</h1>
        <p className="text-sm text-slate-500 mt-1">共 {total} 位用户</p>
      </div>

      {msg && (
        <div className="text-sm text-blue-600 bg-blue-50 rounded-lg px-3 py-2">{msg}</div>
      )}

      {/* 筛选 */}
      <div className="bg-white rounded-2xl shadow-sm p-4 flex flex-wrap items-center gap-3">
        <input
          className="border border-slate-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 w-48"
          placeholder="搜索用户名/昵称"
          value={keyword}
          onChange={(e) => {
            setKeyword(e.target.value)
            setPageNum(1)
          }}
        />
        <select
          className="border border-slate-300 rounded-lg px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          value={roleFilter}
          onChange={(e) => {
            setRoleFilter(e.target.value)
            setPageNum(1)
          }}
        >
          <option value="">全部角色</option>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {roleLabel[r]}
            </option>
          ))}
        </select>
        <button
          onClick={load}
          className="text-sm px-3 py-1.5 rounded-lg bg-slate-100 hover:bg-slate-200 text-slate-700"
        >
          刷新
        </button>
      </div>

      {/* 表格 */}
      <div className="bg-white rounded-2xl shadow-sm overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-slate-50 text-slate-600">
            <tr>
              <th className="text-left px-4 py-3 font-medium">用户名</th>
              <th className="text-left px-4 py-3 font-medium">昵称</th>
              <th className="text-left px-4 py-3 font-medium">角色</th>
              <th className="text-left px-4 py-3 font-medium">注册时间</th>
              <th className="text-left px-4 py-3 font-medium">操作</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr>
                <td colSpan={5} className="text-center py-8 text-slate-400">
                  加载中...
                </td>
              </tr>
            ) : records.length === 0 ? (
              <tr>
                <td colSpan={5} className="text-center py-8 text-slate-400">
                  暂无用户
                </td>
              </tr>
            ) : (
              records.map((u) => (
                <tr key={u.id} className="hover:bg-slate-50">
                  <td className="px-4 py-3 text-slate-800">{u.username}</td>
                  <td className="px-4 py-3 text-slate-700">{u.nickname}</td>
                  <td className="px-4 py-3">
                    <select
                      className="border border-slate-300 rounded-lg px-2 py-1 text-xs focus:outline-none focus:ring-2 focus:ring-blue-500"
                      value={u.role}
                      onChange={(e) => handleRoleChange(u.id, e.target.value)}
                    >
                      {ROLES.map((r) => (
                        <option key={r} value={r}>
                          {roleLabel[r]}
                        </option>
                      ))}
                    </select>
                  </td>
                  <td className="px-4 py-3 text-slate-500 text-xs">
                    {new Date(u.createdAt).toLocaleString('zh-CN')}
                  </td>
                  <td className="px-4 py-3">
                    <button
                      onClick={() => handleDelete(u.id, u.username)}
                      className="text-red-500 hover:text-red-600 text-xs"
                    >
                      删除
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>

        {/* 分页 */}
        <div className="flex items-center justify-between px-4 py-3 border-t border-slate-100 text-sm text-slate-600">
          <span>
            第 {pageNum} / {totalPages} 页
          </span>
          <div className="flex gap-2">
            <button
              disabled={pageNum <= 1}
              onClick={() => setPageNum((p) => p - 1)}
              className="px-3 py-1 rounded-lg bg-slate-100 disabled:opacity-40 hover:bg-slate-200"
            >
              上一页
            </button>
            <button
              disabled={pageNum >= totalPages}
              onClick={() => setPageNum((p) => p + 1)}
              className="px-3 py-1 rounded-lg bg-slate-100 disabled:opacity-40 hover:bg-slate-200"
            >
              下一页
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
