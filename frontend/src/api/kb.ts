import http from './http'
import type { ApiResult, PageResult } from '../types'

export interface KbDocument {
  id: string
  filename: string
  contentType: string | null
  size: number
  chunkCount: number
  status: string
  createdAt: string
}

/** 文档切片（chunkIndex 从 0 开始） */
export interface DocumentChunk {
  id: string
  documentId: string
  content: string
  chunkIndex: number
  vectorId: string
  createdAt: string
}

/** 批量上传中单个文件的处理结果 */
export interface UploadResult {
  filename: string
  success: boolean
  message: string | null
}

// 上传涉及切分+向量化，大文档/批量场景耗时远超默认 15s 超时，这里单独不设超时
export const uploadDocument = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/kb/documents', form, { timeout: 0 }) as Promise<ApiResult<KbDocument>>
}

/** 批量上传：一次提交多个文件，后端线程池并行处理并逐文件返回结果 */
export const uploadDocuments = (files: File[]) => {
  const form = new FormData()
  files.forEach((f) => form.append('files', f))
  return http.post('/kb/documents/batch', form, { timeout: 0 }) as Promise<ApiResult<UploadResult[]>>
}

export const listDocuments = () =>
  http.get('/kb/documents') as Promise<ApiResult<KbDocument[]>>

export const deleteDocument = (id: string) =>
  http.delete(`/kb/documents/${id}`) as Promise<ApiResult<null>>

/** 重试处理失败的文档（从 MinIO 读已保存的原始文件，无需重新上传） */
export const retryDocument = (id: string) =>
  http.post(`/kb/documents/${id}/retry`) as Promise<ApiResult<KbDocument>>

export const listChunks = (documentId: string, page: number, pageSize: number) =>
  http.get(`/kb/documents/${documentId}/chunks`, {
    params: { page, pageSize },
  }) as Promise<ApiResult<PageResult<DocumentChunk>>>
