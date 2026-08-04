import http from './http'
import type { ApiResult, PageResult, UserInfo } from '../types'

/** 修改个人资料请求（仅传需要改的字段） */
export interface UpdateProfileData {
  nickname?: string
  avatar?: string
  bio?: string
}

export interface ChangePasswordData {
  oldPassword: string
  newPassword: string
}

/** 修改个人资料 */
export const updateProfile = (data: UpdateProfileData) =>
  http.put('/auth/profile', data) as Promise<ApiResult<UserInfo>>

/** 修改密码 */
export const changePassword = (data: ChangePasswordData) =>
  http.put('/auth/password', data) as Promise<ApiResult<null>>

/** 上传文件到 MinIO，返回 { objectName, url } */
export const uploadFile = (file: File) => {
  const formData = new FormData()
  formData.append('file', file)
  return http.post('/files/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }) as Promise<ApiResult<{ objectName: string; url: string }>>
}

// ===== 管理员用户管理 =====

/** 用户分页列表 */
export const listUsers = (params: {
  pageNum?: number
  pageSize?: number
  keyword?: string
  role?: string
}) => http.get('/users/list', { params }) as Promise<ApiResult<PageResult<UserInfo>>>

/** 修改用户角色 */
export const updateUserRole = (id: string, role: string) =>
  http.put(`/users/${id}/role`, null, { params: { role } }) as Promise<ApiResult<UserInfo>>

/** 删除用户 */
export const deleteUser = (id: string) =>
  http.delete(`/users/${id}`) as Promise<ApiResult<null>>
