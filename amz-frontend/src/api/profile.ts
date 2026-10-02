import request from './auth'
import type { ApiResponse } from './types'

/**
 * 个人资料（GET /user/getInfo 见 api/auth.ts，本模块只补写侧）。
 *
 * 后端 PUT /user/editInfo 的规则（UserServiceImpl#editInfo）：
 * - 归属只认登录态，DTO 里没有 id，无法改别人的资料；
 * - null = 不修改，空串 = 显式清空（昵称/生日/地址允许清空，性别不允许）；
 * - 列宽是硬约束：nickname 50 / image 500 / address 200 / birthday 20，sex 是 TINYINT；
 *   超限或 sex 非数字会被后端拒绝，而不是等 MySQL 严格模式抛 1406/1366 变成「服务器内部错误」。
 * - DTO 里的 signature / school / identity 三个字段在表里没有列，提交了也不会保存，
 *   所以本页不提供这三个输入框。
 */
export interface ProfileForm {
  nickname?: string
  image?: string
  sex?: string
  birthday?: string
  address?: string
}

/** 与后端 DDL 常量保持一致（改列宽要两边一起改）。 */
export const PROFILE_LIMITS = {
  nickname: 50,
  image: 500,
  address: 200,
  birthday: 20
}

export const editUserInfo = (payload: ProfileForm) =>
  request.put<void, ApiResponse<null>>('/user/editInfo', payload)
