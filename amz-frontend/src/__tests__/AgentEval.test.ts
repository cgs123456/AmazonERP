import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'

import AgentEval from '../views/AgentEval.vue'
import { runAgentEval } from '../api/agentEval'
import type { AgentEvalReport } from '../api/agentEval'

vi.mock('../api/agentEval', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/agentEval')>()
  return { ...actual, runAgentEval: vi.fn() }
})

const report = (over: Partial<AgentEvalReport> = {}): AgentEvalReport => ({
  timestamp: '2026-10-03T01:00:00',
  totalCases: 12,
  passedCount: 9,
  failedCount: 3,
  passRate: 0.75,
  totalDurationMs: 4200,
  agentVersion: 'v3',
  evalMode: 'keyword',
  results: [
    {
      caseId: 'EVAL-001',
      passed: true,
      matchedKeywords: ['库存', '补货'],
      missedKeywords: [],
      durationMs: 120,
      errorMessage: null
    },
    {
      caseId: 'EVAL-004',
      passed: false,
      matchedKeywords: ['物流'],
      missedKeywords: ['成本', '报价'],
      durationMs: 88,
      errorMessage: '超时'
    }
  ],
  ...over
})

const mountPage = () =>
  mount(AgentEval, {
    shallow: true,
    global: { stubs: { AppHeader: true, AppSidebar: true, Icon: true } }
  })

/** 汇总卡里的 k/v，按标签取名值，避免依赖 DOM 顺序 */
const kv = (w: ReturnType<typeof mountPage>) => {
  const map: Record<string, string> = {}
  w.find('[data-panel="summary"]').findAll('.kv').forEach((node) => {
    map[node.find('.k').text()] = node.find('.v').text()
  })
  return map
}

describe('Agent 评测页', () => {
  beforeEach(() => {
    vi.mocked(runAgentEval).mockReset()
  })

  it('缺省以 keyword 模式调用后端，并把汇总数字如实显示', async () => {
    vi.mocked(runAgentEval).mockResolvedValue({ code: 200, message: '操作成功', data: report() })
    const w = mountPage()

    expect(w.find('[data-panel="summary"]').exists()).toBe(false)
    w.find('[data-panel="run"]').trigger('click')
    await flushPromises()

    expect(runAgentEval).toHaveBeenCalledWith('keyword')
    const cells = kv(w)
    expect(cells['用例总数']).toBe('12')
    expect(cells['通过']).toBe('9')
    expect(cells['失败']).toBe('3')
    expect(cells['通过率']).toBe('75.0%')
    expect(cells['总耗时']).toBe('4200 ms')
    expect(cells['Agent 版本']).toBe('v3')
    expect(cells['模式']).toBe('keyword')
  })

  it('逐条判定列出命中与缺失关键词，失败条带错误原文', async () => {
    vi.mocked(runAgentEval).mockResolvedValue({ code: 200, message: '操作成功', data: report() })
    const w = mountPage()
    w.find('[data-panel="run"]').trigger('click')
    await flushPromises()

    const rows = w.find('[data-panel="cases"]').findAll('tbody tr')
    expect(rows).toHaveLength(2)
    expect(rows[0].text()).toContain('EVAL-001')
    expect(rows[0].text()).toContain('通过')
    expect(rows[1].text()).toContain('成本')
    expect(rows[1].text()).toContain('超时')
    expect(rows[1].attributes('data-status')).toBe('fail')
    // 没有缺失关键词的第一行不能显示成空串，要能看出「无」
    expect(rows[0].findAll('td')[3].text()).toBe('—')
  })

  it('both 模式后端拒绝时显示后端文案，且不伪造一份 0 通过的报告', async () => {
    vi.mocked(runAgentEval).mockResolvedValue({
      code: 400,
      message: 'LLM 评估未启用（需 AGENT_LLM_EVAL_ENABLED=true 且配置 deepseek.api-key）',
      data: null as unknown as AgentEvalReport
    })
    const w = mountPage()
    await w.find('select').setValue('both')

    w.find('[data-panel="run"]').trigger('click')
    await flushPromises()

    expect(runAgentEval).toHaveBeenCalledWith('both')
    expect(w.find('.error-zone').text()).toContain('LLM 评估未启用')
    expect(w.find('[data-panel="summary"]').exists()).toBe(false)
    expect(w.find('[data-panel="cases"]').exists()).toBe(false)
  })

  it('后端返回空结果集时说明没有条目，不渲染空表', async () => {
    vi.mocked(runAgentEval).mockResolvedValue({
      code: 200,
      message: '操作成功',
      data: report({ totalCases: 0, passedCount: 0, failedCount: 0, passRate: 0, results: [] })
    })
    const w = mountPage()
    w.find('[data-panel="run"]').trigger('click')
    await flushPromises()

    expect(w.find('[data-panel="cases"]').text()).toContain('没有返回任何用例结果')
    expect(w.find('[data-panel="cases"]').find('table').exists()).toBe(false)
    // 一条都没跑：通过率是「不知道」，不是 0.0%
    expect(kv(w)['通过率']).toBe('—')
  })

  it('运行期间按钮禁用，结束后恢复，避免并发触发两次评测', async () => {
    let release: (v: { code: number; message: string; data: AgentEvalReport }) => void = () => {}
    vi.mocked(runAgentEval).mockReturnValue(
      new Promise((resolve) => {
        release = resolve
      })
    )
    const w = mountPage()

    w.find('[data-panel="run"]').trigger('click')
    await nextTick()
    expect(w.find('[data-panel="run"]').attributes('disabled')).toBeDefined()
    expect(w.find('.control-row .muted').text()).toContain('逐条执行')

    release({ code: 200, message: '操作成功', data: report() })
    await flushPromises()
    expect(w.find('[data-panel="run"]').attributes('disabled')).toBeUndefined()
    expect(w.find('.control-row .muted').text()).toContain('最近一次：keyword 模式')
  })
})
