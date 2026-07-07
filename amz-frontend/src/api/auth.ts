import axios from 'axios'
import type { ApiResponse } from './types'

// 创建 axios 实例
const request = axios.create({
    baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
    timeout: 100000
})

// 请求拦截器
request.interceptors.request.use(
    (config) => {
        const token = localStorage.getItem('token')
        if (token) {
            // 将 token 放在请求头的 token 字段中
            config.headers.token = token
            
            // 检查 token 是否过期（可以通过解析 JWT 或检查时间戳）
            // 这里简单检查 token 是否存在，实际项目中可以添加更复杂的验证
            const tokenExpiry = localStorage.getItem('token_expiry')
            if (tokenExpiry && Date.now() > parseInt(tokenExpiry)) {
                // Token 已过期，清除并跳转登录页
                localStorage.removeItem('token')
                localStorage.removeItem('token_expiry')
                window.location.href = '/'
                return Promise.reject(new Error('Token 已过期'))
            }
        }
        return config
    },
    (error) => {
        return Promise.reject(error)
    }
)

// 响应拦截器
request.interceptors.response.use(
    (response) => {
        return response.data
    },
    (error) => {
        // 401 未授权，清除 token 并跳转登录页
        if (error.response?.status === 401) {
            localStorage.removeItem('token')
            localStorage.removeItem('token_expiry')
            window.location.href = '/'
            return Promise.reject(new Error('未授权，请重新登录'))
        }
        
        return Promise.reject(error)
    }
)

export interface LoginDto {
    phone: string
    code: string
}

export interface UserVo {
    id?: number
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

export interface UserInfoResponse {
    user: UserVo
    age?: number | null
}

// 发送验证码
export const sendVerifyCode = (phone: string) => {
    return request.get<void, ApiResponse<string>>(`/user/send/${phone}`)
}

// 验证登录
export const verifyLogin = (phone: string, code: string) => {
    return request.post<void, ApiResponse<string>>('/user/verify', null, { params: { phone, code } })
}

// 获取用户信息
export const getUserInfo = () => {
    return request.get<void, ApiResponse<UserInfoResponse>>('/user/getInfo')
}

// 关注/取消关注用户
export const toggleFollow = (userId: number) => {
    return request.get<void, ApiResponse<void>>(`/user/attention/${userId}`)
}

// 检查是否已关注用户
export const checkIsFollowed = (userId: number) => {
    return request.get<void, ApiResponse<number>>(`/user/isAttention/${userId}`)
}

export default request
