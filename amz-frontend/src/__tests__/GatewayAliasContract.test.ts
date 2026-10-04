import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { SPAPI_PREFIX_REWRITES, rewriteProxyPath } from '../utils/viteProxy'

/**
 * SP-API 的对外别名在**两个语言、两个目录**里各写了一份：
 * - 开发直连档：`src/utils/viteProxy.ts` 的 `SPAPI_PREFIX_REWRITES`（vite 代理按前缀重写）；
 * - 网关档：`amz-gateway/src/main/resources/application.yml` 的 `RewritePath` 过滤器。
 *
 * 动因（2026-10-03 第四轮覆盖清点）：清点时做了一次「前端调用 → 后端映射」的反向核对，
 * `/connectors/outbox`、`/preflight/shop/{id}`、`/credentials/shop/{id}` 一开始全被判成孤儿，
 * 实体检查才发现它们靠别名落到 `/spapi/**`。两处表各自维护、**没有任何一致性约束**：
 * 改一处忘另一处时，联调档会 404，而本地 hermetic e2e 自己实现别名，照样全绿。
 * 这一族断言就是把那对偶关系钉住。
 */
const GATEWAY_YML = '../amz-gateway/src/main/resources/application.yml'
const SPAPI_CONTROLLER_DIR = '../amz-service/amz-service-spapi/src/main/java/com/amz/controller'

const gateway = readFileSync(GATEWAY_YML, 'utf8')

/** 网关里的别名重写：`RewritePath=/api/<x>(?<segment>.*), /<target>${segment}`。 */
function gatewayRewrites(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  const re = /-\s*RewritePath=\/api\/([a-z-]+)\(\?<segment>\.\*\),\s*(\/[\w/-]+)\$\{segment\}/g
  let m: RegExpExecArray | null
  while ((m = re.exec(text)) !== null) {
    out[`/api/${m[1]}`] = m[2]
  }
  return out
}

/** 某前缀是否有网关原生路由（`Path=/spapi/**`），即不需要别名重写也能到达。 */
function hasNativeRoute(text: string, prefix: string): boolean {
  return text.includes(`Path=${prefix}/**`)
}

/** spapi 各控制器声明的类级前缀。 */
function controllerPrefixes(): Set<string> {
  const found = new Set<string>()
  for (const name of readdirSync(SPAPI_CONTROLLER_DIR)) {
    if (!name.endsWith('.java')) continue
    const t = readFileSync(`${SPAPI_CONTROLLER_DIR}/${name}`, 'utf8')
    // 只认行首的类级注解：javadoc 里举例写的 @RequestMapping 不算声明
    const m = /^@RequestMapping\s*\(\s*"([^"]+)"/m.exec(t)
    if (m) found.add(m[1])
  }
  return found
}

describe('SP-API 别名的两处维护必须一致', () => {
  it('网关 RewritePath 集合与开发代理别名表逐项相等', () => {
    const fromGateway = gatewayRewrites(gateway)
    // /api/spapi 本身在网关是原生路由（Path=/spapi/**），不靠 RewritePath，
    // 所以它只出现在开发代理表里；其余每一项必须两边都有且目标相同。
    const devWithoutNative: Record<string, string> = {}
    for (const [from, to] of Object.entries(SPAPI_PREFIX_REWRITES)) {
      if (from === '/api/spapi' && hasNativeRoute(gateway, '/spapi')) continue
      devWithoutNative[from] = to
    }
    expect(fromGateway).toEqual(devWithoutNative)
    expect(Object.keys(fromGateway).length).toBeGreaterThanOrEqual(3)
  })

  it('每个别名目标前缀都真有对应的 controller 类映射', () => {
    const prefixes = controllerPrefixes()
    expect(prefixes.size).toBeGreaterThan(5)
    for (const to of Object.values(SPAPI_PREFIX_REWRITES)) {
      if (to === '/spapi') continue
      expect(prefixes.has(to), `别名目标 ${to} 在 spapi 没有 @RequestMapping 声明`).toBe(true)
    }
  })

  it('同一个请求路径经开发代理与经网关得到完全相同的目标', () => {
    const samples: Array<[string, string]> = [
      ['/api/connectors', '/api/connectors/outbox'],
      ['/api/connectors', '/api/connectors/rate-limits'],
      ['/api/preflight', '/api/preflight/shop/1'],
      ['/api/credentials', '/api/credentials/shop/1/status']
    ]
    const fromGateway = gatewayRewrites(gateway)
    for (const [prefix, requestPath] of samples) {
      const viaDevProxy = rewriteProxyPath(prefix, requestPath)
      const alias = fromGateway[prefix]
      expect(alias, `网关没有 ${prefix} 的 RewritePath`).toBeTruthy()
      const viaGateway = alias + requestPath.slice(prefix.length)
      expect(viaDevProxy, `${requestPath} 两条路径不一致`).toBe(viaGateway)
    }
  })

  it('别名没变成死面：api 层确实有以别名开头的调用', () => {
    const apiSource = readdirSync('src/api')
      .filter((f) => f.endsWith('.ts'))
      .map((f) => readFileSync(`src/api/${f}`, 'utf8'))
      .join('\n')
    for (const from of Object.keys(SPAPI_PREFIX_REWRITES)) {
      if (from === '/api/spapi') continue
      const bare = from.replace(/^\/api/, '')
      expect(apiSource.includes(`'${bare}/'`) || apiSource.includes(`${bare}/`) ||
        apiSource.includes('`' + bare),
        `别名 ${from} 没有任何前端调用（要么删掉这条别名，要么它已经过期）`).toBe(true)
    }
  })
})
