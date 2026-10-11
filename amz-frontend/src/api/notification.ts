import request from './auth'
import type { ApiResponse } from './types'

/**
 * 站内通知历史（P1.6 消息中心落库）。
 * 写入方是 message 服务（推送时落库，在线/离线都落）；本模块只读 + 标已读。
 */
export interface NotificationRow {
  id: number
  userId: number
  /** MessageTypeEnum 序号：0=库存预警/1=订单异常/2=补货建议/3=差评/4=价格异动 */
  type?: number
  title?: string
  content?: string
  isRead?: number
  createTime?: string
}

/** 当前用户的通知历史（最近 100 条，含已读） */
export const listNotifications = () =>
  request.get<void, ApiResponse<NotificationRow[]>>('/user/notification/list')

/** 标记单条已读 */
export const markNotificationRead = (id: number) =>
  request.post<void, ApiResponse<boolean>>(`/user/notification/${id}/read`)

/** 全部标记已读 */
export const markAllNotificationsRead = () =>
  request.post<void, ApiResponse<boolean>>('/user/notification/read-all')

/** 未读数（顶栏红点） */
export const getUnreadCount = () =>
  request.get<void, ApiResponse<number>>('/user/notification/unread-count')
