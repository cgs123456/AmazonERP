import axios from 'axios'
import type { ApiResponse } from './types'
import { getCurrentShopId } from '../utils/shop'

// 创建 axios 实例
// dev 环境通过 vite proxy 转发 /api → VITE_API_BASE_URL，避免跨域
const request = axios.create({
    baseURL: import.meta.env.DEV ? '/api' : import.meta.env.VITE_API_BASE_URL,
    timeout: 100000
})

// 请求拦截器：统一注入 token 与 shopId 请求头
// 网关 MyGlobalFilter 要求 /shop/ /product/ /order/ 路径必须携带 shopId header
// 且 shopId 在 JWT 授权 shops 列表内。此处集中注入，避免每个 api/*.ts 重复设置。
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
        // 透传当前选中店铺 shopId（未选则为空，由后端网关/业务按需校验）
        const shopId = getCurrentShopId()
        if (shopId) {
            config.headers.shopId = shopId
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
        const payload = response.data
        // 字段级数据权限：后端在 Result 上回填 _hiddenFields（被切面置空的字段名列表）。
        // 前端把 data 中对应字段值替换为 '***'，便于表格列保留占位但不泄露真实数值。
        // 若需彻底隐藏整列，可在业务层基于 _hiddenFields 做列过滤。
        if (payload && Array.isArray(payload._hiddenFields) && payload._hiddenFields.length > 0) {
            const hiddenSet = new Set<string>(payload._hiddenFields)
            maskHiddenFields(payload.data, hiddenSet)
        }
        return payload
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

/**
 * 递归把 data 中名为 hiddenSet 内的字段值替换为 '***'。
 * 支持 object、array、嵌套结构。后端切面已把字段置 null，
 * 这里仅做 UI 层占位渲染，避免业务侧到处判空。
 */
function maskHiddenFields(data: unknown, hiddenSet: Set<string>): void {
    if (data == null || hiddenSet.size === 0) return
    if (Array.isArray(data)) {
        for (const item of data) maskHiddenFields(item, hiddenSet)
        return
    }
    if (typeof data !== 'object') return
    for (const [key, value] of Object.entries(data as Record<string, unknown>)) {
        if (hiddenSet.has(key)) {
            ;(data as Record<string, unknown>)[key] = '***'
        } else if (value && typeof value === 'object') {
            maskHiddenFields(value, hiddenSet)
        }
    }
}

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
    sex?: string
    birthday?: string
    address?: string
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

export default request
