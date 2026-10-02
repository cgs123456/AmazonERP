import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock 视图组件，避免测试环境中动态导入解析 .vue 文件及其副作用依赖
vi.mock('../views/Dashboard.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/OrderList.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/InventoryMonitor.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/AdManager.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/ProfitReport.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/NotificationPage.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/KnowledgeBase.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../views/ConnectorCenter.vue', () => ({ default: { template: '<div />' } }))
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

  it('有 token 访问 /connectors 应匹配连接器状态路由', async () => {
    localStorage.setItem('token', 'fake-token')
    await router.push('/connectors')
    expect(router.currentRoute.value.name).toBe('Connectors')
  })

  it('搜索词规则页有独立路由，且不会被 /ads 的前缀匹配吃掉', async () => {
    localStorage.setItem('token', 'fake-token')
    await router.push('/ad-search-terms')
    expect(router.currentRoute.value.name).toBe('AdSearchTerms')
    await router.push('/ads')
    expect(router.currentRoute.value.name).toBe('Ads')
  })

  it('分时调价页有独立路由，改价通道不能没有入口', async () => {
    localStorage.setItem('token', 'fake-token')
    await router.push('/ad-bid-schedule')
    expect(router.currentRoute.value.name).toBe('AdBidSchedule')
  })
})
