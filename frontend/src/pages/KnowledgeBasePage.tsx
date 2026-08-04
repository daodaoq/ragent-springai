import { useCallback, useEffect, useRef, useState } from 'react'
import { Navigate } from 'react-router-dom'
import {
  deleteDocument,
  listChunks,
  listDocuments,
  uploadDocument,
  type DocumentChunk,
  type KbDocument,
} from '../api/kb'
import { useAuthStore } from '../store/auth'
import { formatTime } from '../utils/format'

export default function KnowledgeBasePage() {
  const { user } = useAuthStore()
  const [docs, setDocs] = useState<KbDocument[]>([])
  const [uploading, setUploading] = useState(false)
  const [error, setError] = useState('')
  const fileRef = useRef<HTMLInputElement>(null)
  // 切片查看模态框
  const [chunkDoc, setChunkDoc] = useState<KbDocument | null>(null)
  const [chunks, setChunks] = useState<DocumentChunk[]>([])
  const [chunkTotal, setChunkTotal] = useState(0)
  const [chunkPage, setChunkPage] = useState(1)
  const [chunkLoading, setChunkLoading] = useState(false)
  const [chunkError, setChunkError] = useState('')

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

  const handleDelete = async (id: string) => {
    if (!window.confirm('确定删除该文档及其全部切片向量？')) return
    try {
      await deleteDocument(id)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }

  const loadChunks = async (docId: string, page: number) => {
    setChunkLoading(true)
    setChunkError('')
    try {
      const res = await listChunks(docId, page, 20)
      setChunks(res.data.records)
      setChunkTotal(res.data.total)
      setChunkPage(page)
    } catch (err) {
      setChunkError(err instanceof Error ? err.message : '加载切片失败')
    } finally {
      setChunkLoading(false)
    }
  }

  const openChunks = async (doc: KbDocument) => {
    setChunkDoc(doc)
    await loadChunks(doc.id, 1)
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
                  <td className="px-4 py-3 text-right whitespace-nowrap">
                    <button onClick={() => openChunks(d)} className="text-blue-500 hover:text-blue-600 mr-3">
                      查看切片
                    </button>
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

      {chunkDoc && (
        <div
          className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
          onClick={() => setChunkDoc(null)}
        >
          <div
            className="bg-white rounded-2xl shadow-xl w-full max-w-3xl h-[80vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-start justify-between px-5 py-4 border-b border-slate-200">
              <div className="min-w-0">
                <h2 className="font-bold text-slate-800 truncate">📄 {chunkDoc.filename}</h2>
                <p className="text-xs text-slate-400 mt-0.5">{chunkTotal} 个切片 · 每页 20 个</p>
              </div>
              <button
                onClick={() => setChunkDoc(null)}
                className="shrink-0 text-slate-400 hover:text-slate-600 text-lg leading-none"
              >
                ✕
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-4 space-y-3">
              {chunkLoading ? (
                <div className="text-slate-400 text-sm py-10 text-center">加载切片中...</div>
              ) : chunkError ? (
                <div className="text-red-500 text-sm py-10 text-center">⚠️ {chunkError}</div>
              ) : chunks.length === 0 ? (
                <div className="text-slate-400 text-sm py-10 text-center">该文档暂无切片</div>
              ) : (
                chunks.map((c) => (
                  <div key={c.id} className="border border-slate-200 rounded-lg overflow-hidden">
                    <div className="flex items-center gap-2 px-3 py-1.5 bg-slate-50 border-b border-slate-100">
                      <span className="px-1.5 py-0.5 rounded bg-blue-600 text-white text-xs font-medium">
                        切片 {c.chunkIndex + 1}
                      </span>
                      <span className="text-xs text-slate-400">共 {c.content.length} 字符</span>
                    </div>
                    <div className="px-3 py-2 text-sm text-slate-700 whitespace-pre-wrap">{c.content}</div>
                  </div>
                ))
              )}
            </div>
            {chunkTotal > 20 && (
              <div className="flex items-center justify-center gap-2 px-5 py-3 border-t border-slate-200">
                <button
                  disabled={chunkPage <= 1}
                  onClick={() => loadChunks(chunkDoc.id, chunkPage - 1)}
                  className="px-3 py-1 rounded border border-slate-300 text-sm disabled:opacity-40"
                >
                  上一页
                </button>
                <span className="text-sm text-slate-500">
                  {chunkPage} / {Math.max(1, Math.ceil(chunkTotal / 20))}
                </span>
                <button
                  disabled={chunkPage >= Math.ceil(chunkTotal / 20)}
                  onClick={() => loadChunks(chunkDoc.id, chunkPage + 1)}
                  className="px-3 py-1 rounded border border-slate-300 text-sm disabled:opacity-40"
                >
                  下一页
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
