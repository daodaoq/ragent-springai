import http from './http'
import type { ApiResult } from '../types'

export interface KbDocument {
  id: number
  filename: string
  contentType: string | null
  size: number
  chunkCount: number
  status: string
  createdAt: string
}

export const uploadDocument = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return http.post('/kb/documents', form) as Promise<ApiResult<KbDocument>>
}

export const listDocuments = () =>
  http.get('/kb/documents') as Promise<ApiResult<KbDocument[]>>

export const deleteDocument = (id: number) =>
  http.delete(`/kb/documents/${id}`) as Promise<ApiResult<null>>
