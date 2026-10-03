/**
 * Agent 评测页 E2E（hermetic：/api/* 由 e2e/support/api-stub.ts 打桩）
 *
 * 断言的是「后端报告里的数在页面上的真实渲染结果」：用例总数、通过、失败、通过率换算、
 * 逐条缺失关键词。以及最关键的一条——后端拒绝时页面显示拒绝原因而不是显示一份 0 通过的报告。
 *
 * 不能证明什么：后端真的会返回这个结构（那由 AiController/AgentEvalRunner 的测试负责）。
 */
import { test, expect } from './support/test'
import type { Page } from '@playwright/test'

const SEED = {
  token: 'e2e-test-token',
  shops: '[{"id":"1","name":"Shop A (US)"}]',
  shop: '1',
  user: '1'
}

test.beforeEach(async ({ page }) => {
  await page.addInitScript((s) => {
    localStorage.setItem('token', s.token)
    localStorage.setItem('token_expiry', String(Date.now() + 86400_000))
    localStorage.setItem('shops', s.shops)
    localStorage.setItem('current_shop_id', s.shop)
    localStorage.setItem('user_id', s.user)
  }, SEED)
})

/** 汇总卡里按标签取值：整词匹配 .k，否则「通过」会同时命中「通过率」而触发严格模式歧义 */
const valueOf = (page: Page, label: string) =>
  page
    .locator('[data-panel="summary"] .kv')
    .filter({ has: page.locator('.k', { hasText: new RegExp(`^${label}$`) }) })
    .locator('.v')

test.describe('Agent 评测 /agent-eval', () => {
  test('关键词模式跑完显示报告原样数字，通过率按比值换算成百分比', async ({ page }) => {
    await page.goto('/agent-eval')
    await expect(page.locator('[data-panel="summary"]')).toHaveCount(0)

    await page.locator('[data-panel="run"]').click()

    await expect(page.locator('[data-panel="summary"]')).toHaveCount(1)
    await expect(valueOf(page, '用例总数')).toHaveText('4')
    await expect(valueOf(page, '通过')).toHaveText('3')
    await expect(valueOf(page, '失败')).toHaveText('1')
    await expect(valueOf(page, '通过率')).toHaveText('75.0%')
    await expect(valueOf(page, '模式')).toHaveText('keyword')
    await expect(valueOf(page, 'Agent 版本')).toHaveText('erp-agent-v1')
  })

  test('逐条判定表按后端结果渲染，失败行带缺失关键词与错误原文', async ({ page }) => {
    await page.goto('/agent-eval')
    await page.locator('[data-panel="run"]').click()

    const rows = page.locator('[data-panel="cases"] tbody tr')
    await expect(rows).toHaveCount(4)
    await expect(rows.nth(0)).toHaveAttribute('data-status', 'pass')
    await expect(rows.nth(3)).toHaveAttribute('data-status', 'fail')
    await expect(rows.nth(3)).toContainText('成本')
    await expect(rows.nth(3)).toContainText('响应缺少成本口径')
    // 通过行没有缺失关键词，必须显式显示「—」而不是空单元格
    await expect(rows.nth(0).locator('td').nth(3)).toHaveText('—')
  })

  test('both 模式被后端拒绝时：显示后端原因，不渲染任何汇总或条目', async ({ page }) => {
    await page.route('**/api/ai/eval/run*', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 400,
          message: 'LLM 评估未启用（需 AGENT_LLM_EVAL_ENABLED=true 且配置 deepseek.api-key）',
          data: null
        })
      })
    )
    await page.goto('/agent-eval')
    // 页头本身有一个店铺下拉，裸 'select' 会命中多个元素
    await page.getByLabel('评测模式').selectOption('both')
    await page.locator('[data-panel="run"]').click()

    await expect(page.locator('.error-zone')).toContainText('LLM 评估未启用')
    await expect(page.locator('[data-panel="summary"]')).toHaveCount(0)
    await expect(page.locator('[data-panel="cases"]')).toHaveCount(0)
  })
})
