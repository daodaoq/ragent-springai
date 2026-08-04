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

export const uploadDocument = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/kb/documents', form) as Promise<ApiResult<KbDocument>>
}

export const listDocuments = () =>
  http.get('/kb/documents') as Promise<ApiResult<KbDocument[]>>

export const deleteDocument = (id: string) =>
  http.delete(`/kb/documents/${id}`) as Promise<ApiResult<null>>

export const listChunks = (documentId: string, page: number, pageSize: number) =>
  http.get(`/kb/documents/${documentId}/chunks`, {
    params: { page, pageSize },
  }) as Promise<ApiResult<PageResult<DocumentChunk>>>
