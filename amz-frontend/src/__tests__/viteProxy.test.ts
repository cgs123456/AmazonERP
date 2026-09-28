import { describe, expect, it } from 'vitest'
import { rewriteProxyPath } from '../utils/viteProxy'

describe('Vite 本地代理路径重写', () => {
  it.each([
    ['/api/spapi', '/api/spapi/orders', '/spapi/orders'],
    ['/api/connectors', '/api/connectors', '/spapi/connectors'],
    ['/api/preflight', '/api/preflight/shop/7', '/spapi/preflight/shop/7'],
    ['/api/credentials', '/api/credentials/shop/7/status', '/spapi/credentials/shop/7/status']
  ])('%s 转发到 SP-API 真实路径', (prefix, requestPath, expected) => {
    expect(rewriteProxyPath(prefix, requestPath)).toBe(expected)
  })

  it('其他微服务继续只去掉 /api，不改变既有行为', () => {
    expect(rewriteProxyPath('/api/order', '/api/order/list')).toBe('/order/list')
    expect(rewriteProxyPath('/api/user', '/api/user/login')).toBe('/user/login')
  })

  it('不会误重写名称相近但不属于该代理前缀的路径', () => {
    expect(rewriteProxyPath('/api/spapi', '/api/spapi-other/ping')).toBe('/spapi-other/ping')
  })
})
