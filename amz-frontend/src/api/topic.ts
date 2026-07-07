import request from './auth'
import type { ApiResponse } from './types'

export interface Topic {
    id?: number
    content?: string
    hot?: number
}

// 获取话题列表
export const getTopics = () => {
    return request.get<void, ApiResponse<Topic[]>>('/note/getTopics')
}
