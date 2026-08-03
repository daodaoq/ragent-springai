import http from './http'
import type { ApiResult, Tag } from '../types'

export const listTags = () =>
  http.get('/tags/list') as Promise<ApiResult<Tag[]>>
