import request from './auth'
import type { ApiResponse } from './types'

export interface NoteVo {
    note: {
        id: string // 后端 long 类型，前端使用 string 避免精度丢失
        title?: string
        content?: string
        image?: string
        time?: string
        type?: string
        address?: string
        longitude?: number
        latitude?: number
        like?: number
        collection?: number
        userId?: number
    }
    user?: {
        id?: number
        phone?: string
        username?: string
        nickname?: string
        image?: string
        number?: string | null
        sex?: string | null
        birthday?: string | null
        address?: string | null
        identity?: string | null
        school?: string | null
        time?: string | null
    } | null
    dealTime?: string | null
    distance?: string | null
    comment?: number | null
}

// 获取笔记列表
export const getNoteList = (page: number, pageSize: number) => {
    return request.get<void, ApiResponse<NoteVo[]>>('/note/getNoteList', {
        params: {
            page,
            pageSize
        }
    })
}

// 获取笔记详情
export const getNoteDetail = (noteId: string | number) => {
    return request.get<void, ApiResponse<NoteVo>>(`/note/getNote/${noteId}`)
}

// 点赞/取消点赞
export const toggleLike = (noteId: string | number) => {
    return request.put<void, ApiResponse<void>>(`/note/like/${noteId}`)
}

// 检查是否已点赞
export const checkIsLiked = (noteId: string | number) => {
    return request.get<void, ApiResponse<boolean>>(`/note/isLike/${noteId}`)
}

// 收藏/取消收藏
export const toggleCollection = (noteId: string | number) => {
    return request.put<void, ApiResponse<void>>(`/note/collection/${noteId}`)
}

// 检查是否已收藏
export const checkIsCollected = (noteId: string | number) => {
    return request.get<void, ApiResponse<boolean>>(`/note/isCollection/${noteId}`)
}

// 点赞列表接口
export interface LikeVo {
    like: {
        id: number
        noteId: string
        ownId: number
        userId: number
        createTime: string
    }
    note: {
        id: string
        title?: string
        content?: string
        image?: string
        time?: string
        type?: string
        address?: string
        longitude?: number
        latitude?: number
        like?: number
        collection?: number
        userId?: number
    }
    user: {
        id: number
        phone?: string
        username?: string
        nickname?: string
        image?: string
        number?: string
        sex?: string
        birthday?: string
        address?: string
        identity?: string
        school?: string
        time?: string
    }
}

// 获取点赞列表（当前用户被点赞的列表）
export const getLikeList = () => {
    return request.get<void, ApiResponse<LikeVo[]>>('/note/getLikeList')
}

// 发布笔记
export const postNote = (formData: FormData) => {
    return request.post<void, ApiResponse<void>>('/note/postNote', formData, {
        headers: {
            'Content-Type': 'multipart/form-data'
        }
    })
}
