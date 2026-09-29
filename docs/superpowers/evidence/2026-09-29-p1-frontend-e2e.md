# P1 前端 E2E 验证证据（2026-09-29）

## 范围

对应交接文档 P1 第 2 项：补齐前端 Playwright/Vitest 覆盖，核心页面包括订单、库存、物流和报表。

本次新增 Playwright 覆盖：

- `/logistics` 物流看板页面渲染断言。
- 侧边栏点击“物流看板”跳转 `/logistics` 断言。

## 代码与分支

- 基线：`136cec0`
- 分支：`codex/p1-frontend-e2e-logistics`
- 覆盖提交：`1cd1a15 test(frontend): cover logistics dashboard in E2E`

## 环境

- OS：Windows
- Node.js：`v22.22.2`
- npm：`10.9.7`
- Playwright：`1.62.1`
- 浏览器：Playwright 自带 Chromium
- 浏览器安装镜像：`https://npmmirror.com/mirrors/playwright`
- 工作目录：`amz-frontend`

## 可复验命令与结果

### Vitest

```powershell
npm run test:run
```

结果：`22 passed` 测试文件，`175 passed` 测试，`0 failed`，`0 skipped`。

覆盖到的核心视图测试文件包括：

- `src/__tests__/OrderList.test.ts`
- `src/__tests__/InventoryMonitor.test.ts`
- `src/__tests__/LogisticsDashboard.test.ts`
- `src/__tests__/ProfitReport.test.ts`
- `src/__tests__/Finance.test.ts`
- `src/__tests__/Warehouse.test.ts`

### Playwright 全页面

```powershell
npx playwright test e2e/all-pages.spec.ts
```

结果：`15 passed`，`0 failed`。

新增物流用例：

- `Logistics › 应渲染物流看板页面`

### Playwright 物流导航

```powershell
npx playwright test e2e/full-interaction.spec.ts -g "物流看板"
```

结果：`1 passed`，`0 failed`。

## 环境限制

- `e2e/all-pages.spec.ts` 在不启动后端时仍可验证页面挂载和前端降级渲染；日志中的 Vite `ECONNREFUSED`/`ETIMEDOUT` 是未启动本地微服务的代理错误，不是断言失败。
- `e2e/full-interaction.spec.ts` 的完整交互断言依赖本地真实后端栈；本次只单独执行并验证了新增的物流侧边栏跳转用例，未宣称完整交互套件已在无后端环境通过。
- 未执行远端 CI，也未进行生产环境或真实 Amazon SP-API 数据联调。
- 本证据不改变“连接器状态上限为 API-Ready”的结论。
