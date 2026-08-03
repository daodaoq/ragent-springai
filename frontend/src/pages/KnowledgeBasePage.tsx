import { useCallback, useEffect, useRef, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { deleteDocument, listDocuments, uploadDocument, type KbDocument } from '../api/kb'
import { useAuthStore } from '../store/auth'
import { formatTime } from '../utils/format'

export default function KnowledgeBasePage() {
  const { user } = useAuthStore()
  const [docs, setDocs] = useState<KbDocument[]>([])
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)

  const load = useCallback(async () => {
    try {
      const res = await listDocuments()
      setDocs(res.data)
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  if (!user) return <Navigate to="/login" replace />

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    setUploading(true)
    setError('')
    try {
      await uploadDocument(file)
      e.target.value = ''
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传失败')
    } finally {
      setUploading(false)
    }
  }

  const handleDelete = async (id: number) => {
    if (!window.confirm('确定删除该文档及其全部切片向量？')) return
    try {
      await deleteDocument(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-bold text-slate-800">📚 知识库管理</h1>
          <p className="text-sm text-slate-500 mt-0.5">上传文档构建实验室知识库，供 AI 检索问答使用（支持 .md / .txt / .pdf）</p>
        </div>
        <div className="flex items-center gap-3">
          {uploading && <span className="text-sm text-slate-400">处理中（切分+向量化）...</span>}
          <button
            onClick={() => fileRef.current?.click()}
            disabled={uploading}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50"
          >
            上传文档
          </button>
          <input ref={fileRef} type="file" accept=".md,.txt,.pdf" className="hidden" onChange={handleUpload} />
        </div>
      </div>

      {error && <div className="text-red-500 text-sm">{error}</div>}

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        {docs.length === 0 ? (
          <div className="text-slate-400 text-sm py-12 text-center">知识库为空，上传第一份文档开始构建</div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-slate-500 border-b border-slate-200">
                <th className="px-4 py-3 font-medium">文件名</th>
                <th className="px-4 py-3 font-medium">切片数</th>
                <th className="px-4 py-3 font-medium">大小</th>
                <th className="px-4 py-3 font-medium">状态</th>
                <th className="px-4 py-3 font-medium">上传时间</th>
                <th className="px-4 py-3 font-medium"></th>
              </tr>
            </thead>
            <tbody>
              {docs.map((d) => (
                <tr key={d.id} className="border-b border-slate-100">
                  <td className="px-4 py-3 text-slate-700">{d.filename}</td>
                  <td className="px-4 py-3 text-slate-500">{d.chunkCount}</td>
                  <td className="px-4 py-3 text-slate-500">{(d.size / 1024).toFixed(1)} KB</td>
                  <td className="px-4 py-3">
                    {d.status === 'READY' ? (
                      <span className="px-2 py-0.5 rounded-full text-xs bg-green-50 text-green-600">可用</span>
                    ) : (
                      <span className="px-2 py-0.5 rounded-full text-xs bg-red-50 text-red-500">{d.status}</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-slate-400">{formatTime(d.createdAt)}</td>
                  <td className="px-4 py-3 text-right">
                    <button onClick={() => handleDelete(d.id)} className="text-red-400 hover:text-red-600">
                      删除
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
