import http from './http'
import type { ApiResult, PageResult } from '../types'

export interface KbDocument {
  id: string
  filename: string
  contentType: string | null
  size: number
  chunkCount: number
  status: string
  /** 原文件内容 SHA-256 */
  fileHash?: string | null
  /** 切片参数覆盖（null = 用全局默认） */
  chunkMaxChars?: number | null
  chunkOverlapChars?: number | null
  chunkSemantic?: boolean | null
  createdAt: string
}

/** 切片参数：null 字段 = 用全局默认（rechunk 时 = 重置回全局） */
export interface ChunkParams {
  maxChunkChars?: number | null
  overlapChars?: number | null
  semantic?: boolean | null
}

/** 批量上传 configs 里单条（按 filename 与文件对齐） */
export interface UploadConfig {
  filename: string
  maxChunkChars?: number | null
  overlapChars?: number | null
  semantic?: boolean | null
}

/** 文档切片（chunkIndex 从 0 开始） */
export interface DocumentChunk {
  id: string
  documentId: string
  content: string
  chunkIndex: number
  vectorId: string
  /** 章节路径（如 "# 第一章 > ## 1.1"） */
  headingPath?: string | null
  /** 切片在原始文档中的行号范围（0 基） */
  lineStart?: number | null
  lineEnd?: number | null
  /** 切片在原始文档中的字符偏移（0 基，开区间） */
  charStart?: number | null
  charEnd?: number | null
  /** PDF 页码（1 基） */
  page?: number | null
  createdAt: string
}

/** 批量上传中单个文件的处理结果 */
export interface UploadResult {
  filename: string
  success: boolean
  message: string | null
}

// 上传涉及切分+向量化，大文档/批量场景耗时远超默认 15s 超时，这里单独不设超时
export const uploadDocument = (file: File, params?: ChunkParams) => {
  const form = new FormData()
  form.append('file', file)
  const q: Record<string, string> = {}
  if (params?.maxChunkChars != null) q.maxChunkChars = String(params.maxChunkChars)
  if (params?.overlapChars != null) q.overlapChars = String(params.overlapChars)
  if (params?.semantic != null) q.semantic = String(params.semantic)
  return http.post('/kb/documents', form, {
    timeout: 0,
    params: q,
  }) as Promise<ApiResult<KbDocument>>
}

/** 批量上传：一次提交多个文件，后端线程池并行处理并逐文件返回结果；configs 按 filename 对齐每文件切片参数 */
export const uploadDocuments = (files: File[], configs?: UploadConfig[]) => {
  const form = new FormData()
  files.forEach((f) => form.append('files', f))
  if (configs && configs.length) form.append('configs', JSON.stringify(configs))
  return http.post('/kb/documents/batch', form, { timeout: 0 }) as Promise<ApiResult<UploadResult[]>>
}

/** 重新切片：从 MinIO 读原始文件按新参数重新切分+向量化（null 字段 = 重置回全局默认） */
export const rechunkDocument = (id: string, params?: ChunkParams) =>
  http.post(`/kb/documents/${id}/rechunk`, params ?? {}) as Promise<ApiResult<KbDocument>>

export const listDocuments = () =>
  http.get('/kb/documents') as Promise<ApiResult<KbDocument[]>>

export const deleteDocument = (id: string) =>
  http.delete(`/kb/documents/${id}`) as Promise<ApiResult<null>>

/** 批量删除多个文档 */
export const deleteDocuments = (ids: string[]) =>
  http.post('/kb/documents/batch-delete', ids) as Promise<ApiResult<null>>

/** 重试处理失败的文档（从 MinIO 读已保存的原始文件，无需重新上传） */
export const retryDocument = (id: string) =>
  http.post(`/kb/documents/${id}/retry`) as Promise<ApiResult<KbDocument>>

export const listChunks = (documentId: string, page: number, pageSize: number) =>
  http.get(`/kb/documents/${documentId}/chunks`, {
    params: { page, pageSize },
  }) as Promise<ApiResult<PageResult<DocumentChunk>>>

/** 原文内容（从 MinIO 读取原始文件提取后的全文文本，供「查看原文」） */
export interface KbDocumentSource {
  filename: string
  contentType: string | null
  text: string
  lineCount: number
}

export const getDocumentSource = (id: string) =>
  http.get(`/kb/documents/${id}/source`) as Promise<ApiResult<KbDocumentSource>>
