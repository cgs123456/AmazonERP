import axios from 'axios'
import type { ApiResponse } from './types'
import { getCurrentShopId } from '../utils/shop'

// refresh token 有效期（与后端 jwt.refresh-expire-time 默认 7 天对齐，见 LoginModal 登录写入）
const REFRESH_TTL_MS = 7 * 24 * 60 * 60 * 1000

// 刷新单飞：并发 401 只打一次 /user/refresh，其余等待同一结果
let refreshPromise: Promise<boolean> | null = null

const persistTokenPair = (data: LoginTokenPair) => {
  localStorage.setItem('token', data.token)
  if (data.refreshToken) {
    localStorage.setItem('refreshToken', data.refreshToken)
  }
  localStorage.setItem('token_expiry', String(Date.now() + REFRESH_TTL_MS))
}

// 用 refreshToken 换取新的 token 对（对齐后端 POST /user/refresh，refresh token 走请求头 token 字段）。
// 用裸 axios 实例（不挂拦截器），避免刷新请求自身 401 触发递归刷新。
export const refreshAccessToken = (): Promise<boolean> => {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    try {
      const refreshToken = localStorage.getItem('refreshToken')
      if (!refreshToken) return false
      // baseURL 缺失回退同源 /api（与 AgentChat 流式基址同策略；VITE_API_BASE_URL 未配时防打到 undefined/…）
      const res = await axios.post<ApiResponse<LoginTokenPair>>(
        `${request.defaults?.baseURL || '/api'}/user/refresh`,
        null,
        { headers: { token: refreshToken } }
      )
      const data = res?.data?.data
      if (res?.data?.code === 200 && data && typeof data.token === 'string' && data.token) {
        persistTokenPair(data)
        return true
      }
      return false
    } catch {
      return false
    } finally {
      refreshPromise = null
    }
  })()
  return refreshPromise
}

const clearAuthAndRedirect = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('token_expiry')
  localStorage.removeItem('refreshToken')
  window.location.href = '/'
}

// 创建 axios 实例
// dev 环境通过 vite proxy 转发 /api → VITE_API_BASE_URL，避免跨域
const request = axios.create({
    baseURL: import.meta.env.DEV ? '/api' : import.meta.env.VITE_API_BASE_URL,
    // 30s：覆盖绝大多数业务接口；AI Agent 等长耗时调用已在各自组件内用原生 fetch 自行控制
    timeout: 30000
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
            // 已在路由守卫中统一处理，此处仅透出 token
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
        // 401 未授权：access token 过期时先用 refreshToken 静默续期并重放原请求一次；
        // 无 refreshToken / 续期失败 / 登录与刷新接口自身 401 时，才清除凭证并跳转登录页
        if (error.response?.status === 401) {
            const original = error.config as (typeof error.config & { _retry?: boolean }) | undefined
            const url: string = original?.url || ''
            const isAuthCall = url.includes('/user/verify') || url.includes('/user/refresh')
            if (!isAuthCall && original && !original._retry && localStorage.getItem('refreshToken')) {
                original._retry = true
                return refreshAccessToken().then((ok) => {
                    if (ok) {
                        original.headers = original.headers || {}
                        ;(original.headers as Record<string, string>).token =
                            localStorage.getItem('token') || ''
                        return request(original)
                    }
                    clearAuthAndRedirect()
                    return Promise.reject(new Error('未授权，请重新登录'))
                })
            }
            clearAuthAndRedirect()
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

/**
 * 后端 POST /user/verify 返回的 token 对（LoginServiceImpl#verify）。
 * data 为 { token, refreshToken } 对象；兼容历史 string 形式。
 */
export interface LoginTokenPair {
    token: string
    refreshToken?: string
}

/**
 * 从登录响应 data 中提取 access token。
 * 后端返回对象 { token, refreshToken }，历史/降级场景可能为裸字符串。
 */
export const extractLoginToken = (data: unknown): string | null => {
    if (typeof data === 'string') return data || null
    if (data && typeof data === 'object' && typeof (data as LoginTokenPair).token === 'string') {
        return (data as LoginTokenPair).token || null
    }
    return null
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
    // 后端 amz_user 有 role 列且 getInfo 一直在返回它，前端此前没声明，
    // 导致全站没有角色来源（见 utils/identity.ts）
    role?: string
}

/**
 * 店铺下拉选项（后端 UserVo.shops / GET /user/shop/list 的元素形状）。
 * 只带 id 与 name：后端刻意不返回凭证或卖家身份信息，前端也不要在这里扩展。
 */
export interface ShopOption {
    id: number | string
    name: string
}

/** 店铺管理页用的完整店铺项（GET /user/shop/list）。 */
export interface ShopListItem extends ShopOption {
    marketplaceId?: string
    region?: string
    sellerId?: string
    /** 1=已授权 0=未授权 -1=授权过期 */
    status?: number
    statusText?: string
    /** 是否在 spapi 登记过凭证（来自 amz_shop_credential，不是本店明文列） */
    credentialConfigured?: boolean
    role?: string
}

export interface UserInfoResponse {
    user: UserVo
    age?: number | null
    /**
     * 当前用户被授权的店铺（真实来源）。
     * 此前前端拿不到任何店铺数据，只能在 main.ts 写死 Shop A/B/C；
     * 后端补上该字段后，登录 → getInfo → setShops() 成为完整链路。
     * 登录成功但这里为空 = 该账号确实没被分配店铺，前端提示联系管理员。
     */
    shops?: ShopOption[]
}

/** 我的店铺列表（含凭证与角色状态；ADMIN 可见全部店铺） */
export const getMyShops = () => {
    return request.get<void, ApiResponse<ShopListItem[]>>('/user/shop/list')
}

/** 创建店铺（ADMIN） */
export const createShop = (payload: {
    shopName: string
    marketplaceId: string
    region?: string
    sellerId?: string
    status?: number
}) => request.post<void, ApiResponse<ShopListItem>>('/user/shop', payload)

/** 更新店铺主数据（ADMIN）；null 字段表示不修改 */
export const updateShop = (
    shopId: number | string,
    payload: { shopName?: string; marketplaceId?: string; region?: string; sellerId?: string; status?: number }
) => request.put<void, ApiResponse<ShopListItem>>(`/user/shop/${shopId}`, payload)

/** 更新店铺授权状态（ADMIN）：1=已授权 0=未授权 -1=授权过期 */
export const updateShopStatus = (shopId: number | string, status: number) =>
    request.post<void, ApiResponse<ShopListItem>>(`/user/shop/${shopId}/status`, null, { params: { status } })

/** 分配用户到店铺（ADMIN）；role 默认 VIEWER */
export const assignShopUser = (shopId: number | string, userId: number | string, role?: string) =>
    request.post<void, ApiResponse<boolean>>(`/user/shop/${shopId}/assign`, null, {
        params: { userId, ...(role ? { role } : {}) }
    })

/** 解除用户-店铺绑定（ADMIN） */
export const unassignShopUser = (shopId: number | string, userId: number | string) =>
    request.post<void, ApiResponse<boolean>>(`/user/shop/${shopId}/assign/remove`, null, {
        params: { userId }
    })

// 发送验证码
export const sendVerifyCode = (phone: string) => {
    return request.get<void, ApiResponse<string>>(`/user/send/${phone}`)
}

// 验证登录（后端返回 { token, refreshToken } 对象，见 extractLoginToken）
export const verifyLogin = (phone: string, code: string) => {
    return request.post<void, ApiResponse<string | LoginTokenPair>>('/user/verify', null, { params: { phone, code } })
}

// 获取用户信息
export const getUserInfo = () => {
    return request.get<void, ApiResponse<UserInfoResponse>>('/user/getInfo')
}

export default request
