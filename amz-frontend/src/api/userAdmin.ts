import request from './auth'
import type { ApiResponse } from './types'

/**
 * 用户/账号管理（P1.4，ADMIN）。
 * 后端语义：角色只支持 ADMIN/OPERATOR/VIEWER；禁修改自己的角色、禁禁用自己；
 * 禁用立即拦登录，但已签发 token 过期前仍有效（鉴权路径不查库的既有取舍）。
 */
export interface UserRow {
  id?: number
  phone?: string
  nickname?: string
  image?: string
  role?: string
  /** 1=正常 0=禁用（V4） */
  status?: number
  createTime?: string
}

/** 用户列表（ADMIN） */
export const listUsers = () =>
  request.get<void, ApiResponse<UserRow[]>>('/user/list')

/** 修改角色（ADMIN） */
export const updateUserRole = (userId: number, role: string) =>
  request.post<void, ApiResponse<UserRow>>(`/user/${userId}/role`, null, { params: { role } })

/** 启用/禁用（ADMIN）：1=正常 0=禁用 */
export const updateUserStatus = (userId: number, status: number) =>
  request.post<void, ApiResponse<UserRow>>(`/user/${userId}/status`, null, { params: { status } })
