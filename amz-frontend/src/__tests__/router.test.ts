import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock 视图组件，避免测试环境中动态导入解析 .vue 文件及其副作用依赖
vi.mock('../views/Dashboard.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/OrderList.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/InventoryMonitor.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/AdManager.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/ProfitReport.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/NotificationPage.vue', () => ({ default: { template: '<div />' } }))
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

  it('访问 / 无需 token', async () => {
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/')
  })
})