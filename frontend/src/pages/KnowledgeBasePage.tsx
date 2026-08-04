import { useCallback, useEffect, useRef, useState } from 'react'
import { Navigate } from 'react-router-dom'
import {
  deleteDocument,
  deleteDocuments,
  getDocumentSource,
  listChunks,
  listDocuments,
  retryDocument,
  uploadDocuments,
  type DocumentChunk,
  type KbDocument,
  type KbDocumentSource,
  type UploadResult,
} from '../api/kb'
import { useAuthStore } from '../store/auth'
import { formatTime } from '../utils/format'

export default function KnowledgeBasePage() {
  const { user } = useAuthStore()
  const [docs, setDocs] = useState<KbDocument[]>([])
  const [uploading, setUploading] = useState(false)
  const [uploadProgress, setUploadProgress] = useState<{ done: number; total: number } | null>(null)
  const [error, setError] = useState('')
  // 上传失败详情（可展开查看全部失败原因）
  const [uploadErrors, setUploadErrors] = useState<{ filename: string; message: string }[]>([])
  const [showUploadErrors, setShowUploadErrors] = useState(false)
  // 同名覆盖确认（Windows 复制冲突风格：把决定权交给用户）
  const [dupeDialog, setDupeDialog] = useState<string[] | null>(null)
  const [dupeChecked, setDupeChecked] = useState<Record<string, boolean>>({})
  // 上传完成后的提示（如「N 个覆盖了旧版本」）
  const [notice, setNotice] = useState('')
  const pendingFilesRef = useRef<File[]>([])
  const fileRef = useRef<HTMLInputElement>(null)
  // 切片查看模态框
  const [chunkDoc, setChunkDoc] = useState<KbDocument | null>(null)
  const [chunks, setChunks] = useState<DocumentChunk[]>([])
  const [chunkTotal, setChunkTotal] = useState(0)
  const [chunkPage, setChunkPage] = useState(1)
  const [chunkLoading, setChunkLoading] = useState(false)
  const [chunkError, setChunkError] = useState('')
  // 查看原文弹窗
  const [sourceDoc, setSourceDoc] = useState<KbDocument | null>(null)
  const [source, setSource] = useState<KbDocumentSource | null>(null)
  const [sourceLoading, setSourceLoading] = useState(false)
  const [sourceError, setSourceError] = useState('')
  // 批量选择与删除确认弹窗（替代系统 window.confirm）
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [confirmState, setConfirmState] = useState<{
    title: string
    message: string
    confirmText?: string
    danger?: boolean
    onConfirm: () => void
  } | null>(null)

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
    const files = e.target.files
    if (!files || files.length === 0) return
    // 批内同名去重（后选者覆盖先选者），避免同批两个同名文件被后端并发线程互相覆盖
    const byName = new Map<string, File>()
    for (const f of Array.from(files)) byName.set(f.name, f)
    const list = Array.from(byName.values())
    e.target.value = '' // 立即清空，保证下次重选同一批文件也能触发 change

    // 与已入库文档比对：存在同名先问用户是否覆盖，不直接覆盖（把决定权交给用户）
    const existingNames = new Set(docs.map((d) => d.filename))
    const dupes = list.filter((f) => existingNames.has(f.name))
    if (dupes.length > 0) {
      const checked: Record<string, boolean> = {}
      dupes.forEach((f) => (checked[f.name] = true)) // 默认勾选覆盖：用户主动选的文件通常就是要更新的
      pendingFilesRef.current = list
      setDupeChecked(checked)
      setDupeDialog(dupes.map((f) => f.name))
      return
    }
    void doUpload(list, [], 0)
  }

  /** 实际执行上传（overwriteNames=本批要覆盖的旧文档名，skippedCount=被用户跳过的重复文件数） */
  const doUpload = async (list: File[], overwriteNames: string[], skippedCount: number) => {
    setUploading(true)
    setUploadProgress({ done: 0, total: list.length })
    setError('')
    setNotice('')
    setUploadErrors([])
    setShowUploadErrors(false)
    try {
      // 分批发送（每批 5 个），每批由后端并行处理；分批是为了实时展示进度，避免传几十个文件时页面像卡死
      const BATCH_SIZE = 5
      const allResults: UploadResult[] = []
      for (let i = 0; i < list.length; i += BATCH_SIZE) {
        const batch = list.slice(i, i + BATCH_SIZE)
        const res = await uploadDocuments(batch)
        allResults.push(...res.data)
        setUploadProgress({ done: Math.min(i + batch.length, list.length), total: list.length })
      }
      const failed = allResults.filter((r) => !r.success)
      if (failed.length > 0) {
        // 摘要只显示成败数量，完整失败原因存起来供「查看详情」展开
        setUploadErrors(failed.map((r) => ({ filename: r.filename, message: r.message ?? '未知错误' })))
        setError(`成功 ${allResults.length - failed.length}/${allResults.length} 个，失败 ${failed.length} 个`)
      } else {
        const overwrote = allResults.filter((r) => r.success && overwriteNames.includes(r.filename)).length
        const tips: string[] = []
        if (overwrote > 0) tips.push(`${overwrote} 个覆盖了旧版本`)
        if (skippedCount > 0) tips.push(`跳过 ${skippedCount} 个已存在文件`)
        if (tips.length > 0) setNotice(`上传完成：${tips.join('，')}`)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '上传失败')
    } finally {
      await load()
      setUploading(false)
      setUploadProgress(null)
    }
  }

  /** 覆盖确认弹窗里点击「上传选中」：只上传非重复文件 + 用户勾选要覆盖的重复文件 */
  const confirmDupeUpload = () => {
    const dupes = dupeDialog ?? []
    const overwriteSet = new Set(dupes.filter((n) => dupeChecked[n]))
    const skipped = dupes.filter((n) => !dupeChecked[n])
    const overwriteNames = dupes.filter((n) => dupeChecked[n])
    const list = pendingFilesRef.current.filter((f) => !dupes.includes(f.name) || overwriteSet.has(f.name))
    setDupeDialog(null)
    pendingFilesRef.current = []
    void doUpload(list, overwriteNames, skipped.length)
  }

  const handleDelete = (d: KbDocument) => {
    setConfirmState({
      title: '删除文档',
      message: `确定删除「${d.filename}」？该文档的切片、向量和 MinIO 原始文件会一并删除，此操作不可恢复。`,
      onConfirm: async () => {
        try {
          await deleteDocument(d.id)
          await load()
        } catch (err) {
          setError(err instanceof Error ? err.message : '删除失败')
        } finally {
          setConfirmState(null)
        }
      },
    })
  }

  const handleRetry = (d: KbDocument) => {
    setConfirmState({
      title: '重新处理文档',
      message: `重新处理「${d.filename}」？将从已保存的原始文件重新切分+向量化，无需重新上传。`,
      confirmText: '重新处理',
      danger: false,
      onConfirm: async () => {
        try {
          await retryDocument(d.id)
          await load()
        } catch (err) {
          setError(err instanceof Error ? err.message : '重试失败')
        } finally {
          setConfirmState(null)
        }
      },
    })
  }

  const toggleSelect = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const toggleSelectAll = () => {
    setSelected((prev) => (prev.size === docs.length ? new Set() : new Set(docs.map((d) => d.id))))
  }

  const handleBatchDelete = () => {
    const names = docs
      .filter((d) => selected.has(d.id))
      .slice(0, 3)
      .map((d) => d.filename)
      .join('、')
    const message =
      selected.size > 3
        ? `确定删除选中的 ${selected.size} 个文档（${names} 等）？切片、向量和 MinIO 原始文件一并删除，不可恢复。`
        : `确定删除选中的 ${selected.size} 个文档？切片、向量和 MinIO 原始文件一并删除，不可恢复。`
    setConfirmState({
      title: '批量删除',
      message,
      onConfirm: async () => {
        try {
          await deleteDocuments(Array.from(selected))
          setSelected(new Set())
          await load()
        } catch (err) {
          setError(err instanceof Error ? err.message : '删除失败')
        } finally {
          setConfirmState(null)
        }
      },
    })
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

  const openSource = async (doc: KbDocument) => {
    setSourceDoc(doc)
    setSource(null)
    setSourceError('')
    setSourceLoading(true)
    try {
      const res = await getDocumentSource(doc.id)
      setSource(res.data)
    } catch (err) {
      setSourceError(err instanceof Error ? err.message : '加载原文失败')
    } finally {
      setSourceLoading(false)
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-lg font-bold text-slate-800">📚 知识库管理</h1>
          <p className="text-sm text-slate-500 mt-0.5">上传文档构建实验室知识库，供 AI 检索问答使用（支持 .md / .txt / .pdf / .docx）</p>
        </div>
        <div className="flex items-center gap-3">
          {uploading && uploadProgress && (
            <div className="flex items-center gap-2">
              <span className="text-sm text-slate-400">
                正在处理文档 {uploadProgress.done}/{uploadProgress.total}（切分+向量化）
              </span>
              <div className="w-32 h-1.5 bg-slate-200 rounded-full overflow-hidden">
                <div
                  className="h-full bg-blue-500 rounded-full transition-all duration-300"
                  style={{ width: `${(uploadProgress.done / uploadProgress.total) * 100}%` }}
                />
              </div>
            </div>
          )}
          <button
            onClick={() => fileRef.current?.click()}
            disabled={uploading}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50"
          >
            上传文档
          </button>
          <input ref={fileRef} type="file" accept=".md,.txt,.pdf,.docx" multiple className="hidden" onChange={handleUpload} />
        </div>
      </div>

      {error && (
        <div className="text-red-500 text-sm">
          <div className="flex items-center gap-2">
            <span>⚠️ {error}</span>
            {uploadErrors.length > 0 && (
              <button
                onClick={() => setShowUploadErrors(!showUploadErrors)}
                className="text-blue-500 hover:text-blue-600 underline text-xs shrink-0"
              >
                {showUploadErrors ? '收起详情' : `查看详情（${uploadErrors.length} 个）`}
              </button>
            )}
          </div>
          {showUploadErrors && uploadErrors.length > 0 && (
            <div className="mt-2 max-h-64 overflow-y-auto bg-red-50 border border-red-100 rounded-lg p-3 text-xs space-y-1">
              {uploadErrors.map((f, i) => (
                <div key={i} className="break-all">
                  <span className="text-red-600 font-medium">{f.filename}</span>
                  <span className="text-red-400">：{f.message}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {notice && <div className="text-amber-600 text-sm">ℹ️ {notice}</div>}

      {selected.size > 0 && (
        <div className="flex items-center gap-3 bg-blue-50 border border-blue-100 rounded-lg px-4 py-2 text-sm">
          <span className="text-blue-700 font-medium">已选 {selected.size} 个</span>
          <button onClick={() => setSelected(new Set())} className="text-slate-500 hover:text-slate-700">
            取消选择
          </button>
          <button
            onClick={handleBatchDelete}
            className="ml-auto px-3 py-1 rounded-lg bg-red-600 text-white text-xs font-medium hover:bg-red-700"
          >
            批量删除
          </button>
        </div>
      )}

      <div className="bg-white rounded-xl border border-slate-200 overflow-hidden">
        {docs.length === 0 ? (
          <div className="text-slate-400 text-sm py-12 text-center">知识库为空，上传第一份文档开始构建</div>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-slate-500 border-b border-slate-200">
                <th className="px-4 py-3 font-medium w-10">
                  <input
                    type="checkbox"
                    checked={docs.length > 0 && selected.size === docs.length}
                    onChange={toggleSelectAll}
                    className="accent-blue-600"
                  />
                </th>
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
                  <td className="px-4 py-3">
                    <input
                      type="checkbox"
                      checked={selected.has(d.id)}
                      onChange={() => toggleSelect(d.id)}
                      className="accent-blue-600"
                    />
                  </td>
                  <td className="px-4 py-3 text-slate-700">{d.filename}</td>
                  <td className="px-4 py-3 text-slate-500">{d.chunkCount}</td>
                  <td className="px-4 py-3 text-slate-500">{(d.size / 1024).toFixed(1)} KB</td>
                  <td className="px-4 py-3">
                    {d.status === 'READY' ? (
                      <span className="px-2 py-0.5 rounded-full text-xs bg-green-50 text-green-600">可用</span>
                    ) : d.status === 'FAILED' ? (
                      <span className="px-2 py-0.5 rounded-full text-xs bg-red-50 text-red-500">处理失败</span>
                    ) : (
                      <span className="px-2 py-0.5 rounded-full text-xs bg-yellow-50 text-yellow-600">{d.status}</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-slate-400">{formatTime(d.createdAt)}</td>
                  <td className="px-4 py-3 text-right whitespace-nowrap">
                    <button onClick={() => openSource(d)} className="text-emerald-500 hover:text-emerald-600 mr-3">
                      查看原文
                    </button>
                    <button onClick={() => openChunks(d)} className="text-blue-500 hover:text-blue-600 mr-3">
                      查看切片
                    </button>
                    {d.status !== 'READY' && (
                      <button onClick={() => handleRetry(d)} className="text-amber-500 hover:text-amber-600 mr-3">
                        重试
                      </button>
                    )}
                    <button onClick={() => handleDelete(d)} className="text-red-400 hover:text-red-600">
                      删除
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      {dupeDialog && (
        <div
          className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
          onClick={() => {
            setDupeDialog(null)
            pendingFilesRef.current = []
          }}
        >
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-md" onClick={(e) => e.stopPropagation()}>
            <div className="px-5 py-4 border-b border-slate-200">
              <h2 className="font-bold text-slate-800">⚠️ 检测到 {dupeDialog.length} 个同名文档</h2>
              <p className="text-xs text-slate-400 mt-0.5">勾选 = 覆盖旧版本；取消勾选 = 跳过该文件，保留旧版本</p>
            </div>
            <div className="max-h-64 overflow-y-auto px-5 py-3 space-y-2">
              {dupeDialog.map((name) => (
                <label key={name} className="flex items-center gap-2 text-sm text-slate-700 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={!!dupeChecked[name]}
                    onChange={() => setDupeChecked((s) => ({ ...s, [name]: !s[name] }))}
                    className="accent-blue-600 shrink-0"
                  />
                  <span className="break-all">{name}</span>
                </label>
              ))}
            </div>
            <div className="flex items-center justify-between px-5 py-4 border-t border-slate-200">
              <div className="flex gap-2">
                <button
                  onClick={() => setDupeChecked(Object.fromEntries(dupeDialog.map((n) => [n, true])))}
                  className="px-3 py-1.5 rounded-lg border border-slate-300 text-sm hover:bg-slate-50"
                >
                  全部覆盖
                </button>
                <button
                  onClick={() => setDupeChecked(Object.fromEntries(dupeDialog.map((n) => [n, false])))}
                  className="px-3 py-1.5 rounded-lg border border-slate-300 text-sm hover:bg-slate-50"
                >
                  全部跳过
                </button>
              </div>
              <div className="flex gap-2">
                <button
                  onClick={() => {
                    setDupeDialog(null)
                    pendingFilesRef.current = []
                  }}
                  className="px-3 py-1.5 rounded-lg text-slate-500 text-sm hover:bg-slate-50"
                >
                  取消
                </button>
                <button
                  onClick={confirmDupeUpload}
                  className="px-3 py-1.5 rounded-lg bg-blue-600 text-white text-sm hover:bg-blue-700"
                >
                  上传选中（{pendingFilesRef.current.length - dupeDialog.length + dupeDialog.filter((n) => dupeChecked[n]).length}）
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

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
                      <span className="text-xs text-slate-400">
                        共 {c.content.length} 字符
                        {c.charStart != null && c.charEnd != null && (
                          <> · 字符 {c.charStart}-{c.charEnd}</>
                        )}
                        {c.lineStart != null && (
                          <>
                            {' · '}
                            {c.lineEnd != null ? `第 ${c.lineStart + 1}-${c.lineEnd + 1} 行` : `第 ${c.lineStart + 1} 行`}
                            {c.page != null ? ` · 第 ${c.page} 页` : ''}
                          </>
                        )}
                      </span>
                    </div>
                    {c.headingPath && (
                      <div className="px-3 py-1 bg-amber-50/60 text-xs text-slate-500 border-b border-slate-100">📑 {c.headingPath}</div>
                    )}
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

      {sourceDoc && (
        <div
          className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
          onClick={() => setSourceDoc(null)}
        >
          <div
            className="bg-white rounded-2xl shadow-xl w-full max-w-3xl h-[80vh] flex flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-start justify-between px-5 py-4 border-b border-slate-200">
              <div className="min-w-0">
                <h2 className="font-bold text-slate-800 truncate">📄 原文：{sourceDoc.filename}</h2>
                <p className="text-xs text-slate-400 mt-0.5">
                  {source ? `${source.lineCount} 行 · ${source.contentType ?? '未知类型'}` : ' '}
                  {sourceDoc.contentType?.toLowerCase().includes('pdf') ? ' · PDF 展示为提取文本' : ''}
                </p>
              </div>
              <button
                onClick={() => setSourceDoc(null)}
                className="shrink-0 text-slate-400 hover:text-slate-600 text-lg leading-none"
              >
                ✕
              </button>
            </div>
            <div className="flex-1 overflow-y-auto px-5 py-4">
              {sourceLoading ? (
                <div className="text-slate-400 text-sm py-10 text-center">加载原文中...</div>
              ) : sourceError ? (
                <div className="text-red-500 text-sm py-10 text-center">⚠️ {sourceError}</div>
              ) : (
                <pre className="text-sm text-slate-700 whitespace-pre-wrap font-sans leading-relaxed">
                  {source?.text}
                </pre>
              )}
            </div>
          </div>
        </div>
      )}

      {confirmState && (
        <div
          className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4"
          onClick={() => setConfirmState(null)}
        >
          <div className="bg-white rounded-2xl shadow-xl w-full max-w-md" onClick={(e) => e.stopPropagation()}>
            <div className="px-5 py-4 border-b border-slate-200">
              <h2 className="font-bold text-slate-800">{confirmState.title}</h2>
            </div>
            <div className="px-5 py-4 text-sm text-slate-600 leading-relaxed">{confirmState.message}</div>
            <div className="flex items-center justify-end gap-2 px-5 py-4 border-t border-slate-200">
              <button
                onClick={() => setConfirmState(null)}
                className="px-3 py-1.5 rounded-lg text-slate-500 text-sm hover:bg-slate-50"
              >
                取消
              </button>
              <button
                onClick={confirmState.onConfirm}
                className={`px-3 py-1.5 rounded-lg text-white text-sm ${
                  confirmState.danger === false ? 'bg-blue-600 hover:bg-blue-700' : 'bg-red-600 hover:bg-red-700'
                }`}
              >
                {confirmState.confirmText ?? '确认删除'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
