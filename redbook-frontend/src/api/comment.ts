import request from './auth'
import type { ApiResponse } from './types'

export interface CommentDto {
    noteId: string
    content: string
    userId?: number
    parentId?: string
}

export interface Comment {
    id: number
    noteId: string
    userId: number
    content: string
    time: string
    parentId?: number | null
}

export interface CommentVo {
    comment: Comment
    commentVoId: string
    dealTime: string
    user?: {
        id: number
        nickname?: string
        image?: string
        username?: string
    }
    childrenList?: CommentVo[]
}

// 发表评论
export const postComment = (commentDto: CommentDto) => {
    return request.post<void, ApiResponse<void>>('/comment/postComment', commentDto)
}

// 获取评论列表
export const getCommentList = (noteId: string) => {
    return request.get<void, ApiResponse<CommentVo[]>>(`/comment/getCommentList/${noteId}`)
}

// 获取评论数量
export const getCommentCount = (noteId: string) => {
    return request.get<void, ApiResponse<number>>(`/comment/getCommentCount/${noteId}`)
}
