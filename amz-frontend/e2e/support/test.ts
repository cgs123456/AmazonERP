/**
 * 共享 Playwright fixture：给每个用例自动装上 hermetic API 打桩。
 *
 * 用法：spec 里 `import { test, expect } from './support/test'`，
 * 不要再从 '@playwright/test' 直接导入 test——那样会绕过打桩，回到
 * 「依赖真实后端」的不可复现状态。
 */
import { test as base, expect } from '@playwright/test'
import { installApiStub } from './api-stub'

export const test = base.extend<{ apiStub: void }>({
  apiStub: [
    async ({ page }, use) => {
      // 未处理的页面异常（Vue render/update 抛错等）必须让用例失败。
      // 不加这条时，一个把页面打进渲染异常的 bug 仍然能被「页面标题非空」
      // 这类弱断言放过：骨架屏不消失、内容永远不出来，测试却绿。
      // 2026-09-30 实测就是这样漏掉的（sales-trend 桩返回对象 -> for...of 抛
      // TypeError -> Dashboard 永远停在骨架屏，而 .kpi-grid 断言照样通过）。
      const pageErrors: string[] = []
      page.on('pageerror', (err) => pageErrors.push(String(err)))

      await installApiStub(page)
      await use()

      if (pageErrors.length > 0) {
        throw new Error('页面出现未处理异常：' + pageErrors.join(' | '))
      }
    },
    { auto: true }
  ]
})

export { expect }
