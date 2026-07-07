import request from './auth'
import type { ApiResponse } from './types'

export interface Product {
    id: number
    name: string
    type: string
    description: string
    brand: string
    price: number
    image: string
    time: string
    sales: number
    userId: number
    shopId: number
    stock: number
}

export interface CustomAttribute {
    label: string
    value: string[]
}

export interface ProductVo {
    product: Product
    shop?: {
        id?: number
        name?: string
    }
    customAttributes?: CustomAttribute[]
}

export interface Coupon {
    id: number
    name: string
    type: string
    discount: number
    startTime: string
    endTime: string
    stock: number
}

export interface CouponVo {
    coupon: Coupon
    isUsable: boolean
}

export interface BuyDto {
    productId: number
    selectAttributes: Array<{
        label: string
        value: string[]
    }>
    userId?: number
    couponId?: number
}

// 获取商品列表
export const getProductList = (page?: number, size?: number) => {
    const params: Record<string, number> = {}
    if (page !== undefined) params.page = page
    if (size !== undefined) params.size = size
    return request.get<void, ApiResponse<Product[]>>('/product/getProductList', { params })
}

// 获取商品详情
export const getProduct = (productId: number) => {
    return request.get<void, ApiResponse<ProductVo>>(`/product/getProduct/${productId}`)
}

// 获取店铺商品列表
export const getProductsByShop = (productId: number) => {
    return request.get<void, ApiResponse<Product[]>>(`/product/getProductsByShop/${productId}`)
}

// 搜索推荐
export const recommend = (search: string) => {
    return request.get<void, ApiResponse<string[]>>('/product/recommend', {
        params: { search }
    })
}

// 立即购买
export const buyProduct = (buyDto: BuyDto) => {
    return request.post<void, ApiResponse<void>>('/product/buyProduct', buyDto)
}

// 获取用户优惠券列表
export const getCouponsByUserId = () => {
    return request.get<void, ApiResponse<CouponVo[]>>('/product/getCouponsByUserId')
}

// 使用优惠券
export const useCoupon = (couponId: number) => {
    return request.post<void, ApiResponse<void>>(`/product/useCoupon/${couponId}`)
}

// AI 聊天接口（通过 product 服务代理）
export const chatWithAgent = (message: string) => {
    return request.post<void, ApiResponse<string>>('/product/agent/buyProduct', message, {
        headers: {
            'Content-Type': 'text/plain'
        }
    })
}

// 购物车相关接口
export interface Cart {
    id: number
    userId: number
    productId: number
    productName: string
    productImage: string
    productPrice: number
    quantity: number
    createTime: string
    updateTime: string
}

// 添加商品到购物车
export const addToCart = (productId: number, quantity: number = 1) => {
    return request.post<void, ApiResponse<void>>('/cart/addToCart', null, {
        params: { productId, quantity }
    })
}

// 获取用户购物车列表
export const getCartList = () => {
    return request.get<void, ApiResponse<Cart[]>>('/cart/getCartList')
}

// 更新购物车商品数量
export const updateCartQuantity = (cartId: number, quantity: number) => {
    return request.put<void, ApiResponse<void>>('/cart/updateQuantity', null, {
        params: { cartId, quantity }
    })
}

// 删除购物车商品
export const removeFromCart = (cartId: number) => {
    return request.delete<void, ApiResponse<void>>(`/cart/remove/${cartId}`)
}

// 清空购物车
export const clearCart = () => {
    return request.delete<void, ApiResponse<void>>('/cart/clear')
}
