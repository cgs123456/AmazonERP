/**
 * Vite 本地开发代理路径重写。
 *
 * 前端请求统一以 `/api` 开头；多数微服务直接去掉 `/api`，但 SP-API
 * 的网关别名需要映射回服务真实前缀 `/spapi/**`。
 */
/**
 * 对外别名 → 服务真实前缀。
 * 导出不是为了测试方便：这张表与 amz-gateway 的 RewritePath 必须成对存在，
 * 任何一处单独漂移都会让前端 404 而本地 hermetic e2e 全绿（别名由桩自己实现）。
 * 一致性由 src/__tests__/GatewayAliasContract.test.ts 钉住。
 */
export const SPAPI_PREFIX_REWRITES: Readonly<Record<string, string>> = {
  '/api/spapi': '/spapi',
  '/api/connectors': '/spapi/connectors',
  '/api/preflight': '/spapi/preflight',
  '/api/credentials': '/spapi/credentials'
}

export function rewriteProxyPath(prefix: string, requestPath: string): string {
  const targetPrefix = SPAPI_PREFIX_REWRITES[prefix]
  if (targetPrefix && (requestPath === prefix || requestPath.startsWith(`${prefix}/`))) {
    return `${targetPrefix}${requestPath.slice(prefix.length)}`
  }
  return requestPath.replace(/^\/api/, '')
}
