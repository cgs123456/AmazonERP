import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock 视图组件，避免测试环境中动态导入解析 .vue 文件及其副作用依赖
vi.mock('../views/Dashboard.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/OrderList.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/InventoryMonitor.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/AdManager.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/ProfitReport.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/NotificationPage.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/KnowledgeBase.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/NotFound.vue', () => ({ default: { template: '<div />' } }))

import router from '../router'

describe('路由守卫鉴权', () => {
  beforeEach(() => {
    localStorage.clear()
    // 重置浏览器历史，避免上一个用例的路径残留
    window.history.replaceState({}, '', '/')
  })

  it('无 token 访问受保护路由应重定向到 /', async () => {
    await router.push('/orders')
    // 守卫拦截后应重定向到首页
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('有 token 访问受保护路由应放行', async () => {
    localStorage.setItem('token', 'fake-token')
    await router.push('/orders')
    expect(router.currentRoute.value.path).toBe('/orders')
  })

  it('token_expiry 被污染为非数字时应按过期清理（防 NaN 穿透守卫）', async () => {
    localStorage.setItem('token', 'fake-token')
    localStorage.setItem('token_expiry', 'garbage')
    // 注：不用 /orders（上一个用例已停在该路径，重复 push 不触发守卫）
    await router.push('/inventory')
    expect(router.currentRoute.value.path).toBe('/')
    expect(localStorage.getItem('token')).toBeNull()
  })

  it('访问 / 无需 token', async () => {
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('无 token 访问 /knowledge 应重定向到 /', async () => {
    await router.push('/knowledge')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('有 token 访问 /knowledge 应放行', async () => {
    localStorage.setItem('token', 'fake-token')
    await router.push('/knowledge')
    expect(router.currentRoute.value.path).toBe('/knowledge')
  })
})