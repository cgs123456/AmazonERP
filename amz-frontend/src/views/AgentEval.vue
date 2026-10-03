<template>
  <div class="eval-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">Agent 评测回归</h1>
        <p class="hero-subtitle">跑一遍内置用例，确认改过提示词后工具选择与回答质量没有退化</p>
      </div>

      <div v-if="error" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="18" />
        <span class="error-text">{{ error }}</span>
        <button class="action-btn" :disabled="running" @click="run">重试</button>
      </div>

      <div class="notice-zone" role="note">
        <div class="notice-title">这两个模式的差别是事实差别，不是口味差别</div>
        <ul class="notice-list">
          <li>关键词判定（缺省）：只跑仓库里的用例集并按关键词命中判定，**不需要模型密钥**，
            所以它在任何环境下都能出真实结果。</li>
          <li>关键词 + LLM 打分：额外要求 <code>AGENT_LLM_EVAL_ENABLED=true</code> 且配置
            <code>deepseek.api-key</code>。不满足时后端直接返回失败说明，
            本页把它当失败显示——不会退化成「0 条通过」这种看起来像结果的东西。</li>
          <li>每次运行后端会尽量落库 <code>amz_agent_eval_log</code>；落库失败不影响本次报告，
            所以「页面上有结果」不等于「库里一定有历史」。</li>
          <li>通过率是用例数的比值（通过 ÷ 总用例）。一条没跑时它显示「—」而不是 0%，
            两者含义不同。</li>
        </ul>
      </div>

      <div class="table-card control-card">
        <h2 class="card-title">运行</h2>
        <div class="control-row">
          <label class="field">
            <span class="field-label">模式</span>
            <select v-model="mode" class="field-input" :disabled="running" aria-label="评测模式">
              <option v-for="m in EVAL_MODES" :key="m.code" :value="m.code">{{ m.label }}</option>
            </select>
          </label>
          <button class="primary-btn" data-panel="run" :disabled="running" @click="run">
            {{ running ? '运行中…' : '开始评测' }}
          </button>
          <span v-if="running" class="muted" role="status">评测在用例上逐条执行，请稍候</span>
          <span v-else-if="lastRunLabel" class="muted">{{ lastRunLabel }}</span>
        </div>
      </div>

      <div v-if="report" class="table-card summary-card" data-panel="summary">
        <h2 class="card-title">本次结果</h2>
        <div class="kv-grid">
          <div class="kv"><span class="k">用例总数</span><span class="v mono">{{ count(totalCases) }}</span></div>
          <div class="kv"><span class="k">通过</span><span class="v mono pass">{{ count(passed) }}</span></div>
          <div class="kv"><span class="k">失败</span><span class="v mono fail">{{ count(failed) }}</span></div>
          <div class="kv"><span class="k">通过率</span><span class="v mono">{{ passRateText }}</span></div>
          <div class="kv"><span class="k">总耗时</span><span class="v mono">{{ ms(totalDurationMs) }}</span></div>
          <div class="kv"><span class="k">模式</span><span class="v mono">{{ text(report.evalMode) }}</span></div>
          <div class="kv"><span class="k">Agent 版本</span><span class="v mono">{{ text(report.agentVersion) }}</span></div>
          <div class="kv"><span class="k">运行时间</span><span class="v mono">{{ text(report.timestamp) }}</span></div>
        </div>
      </div>

      <div v-if="report" class="table-card cases-card" data-panel="cases">
        <h2 class="card-title">逐条判定</h2>
        <p v-if="!cases.length" class="muted">后端本次没有返回任何用例结果，页面不编造条目。</p>
        <table v-else class="case-table">
          <thead>
            <tr>
              <th>用例</th>
              <th>判定</th>
              <th>命中关键词</th>
              <th>缺失关键词</th>
              <th>耗时</th>
              <th>错误</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(c, i) in cases" :key="c.caseId || i" :data-status="c.passed ? 'pass' : 'fail'">
              <td class="mono">{{ text(c.caseId) }}</td>
              <td>
                <span :class="c.passed ? 'tag pass' : 'tag fail'">{{ c.passed ? '通过' : '失败' }}</span>
              </td>
              <td class="kw">{{ c.matchedKeywords && c.matchedKeywords.length ? c.matchedKeywords.join('、') : '—' }}</td>
              <td class="kw">{{ c.missedKeywords && c.missedKeywords.length ? c.missedKeywords.join('、') : '—' }}</td>
              <td class="mono">{{ ms(c.durationMs) }}</td>
              <td class="err">{{ text(c.errorMessage) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { EVAL_MODES, runAgentEval } from '../api/agentEval'
import type { AgentEvalCaseResult, AgentEvalReport, EvalMode } from '../api/agentEval'

const mode = ref<EvalMode>('keyword')
const running = ref(false)
const error = ref('')
const report = ref<AgentEvalReport | null>(null)
// 记下这次报告是哪个模式跑出来的：mode 本身可以在结果出来后立刻被改掉
const ranMode = ref('')

/** 后端 passRate 是 0~1 的比值；null/undefined 一律当「不知道」，不按 0 显示。 */
const num = (v?: number | null): number | null =>
  typeof v === 'number' && Number.isFinite(v) ? v : null
const text = (v?: string | number | null) =>
  v === undefined || v === null || v === '' ? '—' : String(v)
const count = (v: number | null) => (v === null ? '—' : String(v))
const ms = (v?: number | null) => {
  const n = num(v)
  return n === null ? '—' : `${n} ms`
}

const totalCases = computed(() => num(report.value?.totalCases))
const passed = computed(() => num(report.value?.passedCount))
const failed = computed(() => num(report.value?.failedCount))
const totalDurationMs = computed(() => num(report.value?.totalDurationMs))
const cases = computed<AgentEvalCaseResult[]>(() => report.value?.results ?? [])

const passRateText = computed(() => {
  const r = num(report.value?.passRate)
  if (r === null) return '—'
  // 一条用例都没跑时 0.0 的含义是「没有结果」，不是「0% 通过」
  if ((totalCases.value ?? 0) <= 0) return '—'
  return `${(r * 100).toFixed(1)}%`
})

const lastRunLabel = computed(() =>
  ranMode.value ? `最近一次：${ranMode.value} 模式` : '尚未运行'
)

const run = async () => {
  running.value = true
  error.value = ''
  try {
    const res = await runAgentEval(mode.value)
    if (!res || res.code !== 200) {
      report.value = null
      error.value = res?.message || '评测运行失败：后端没有返回结果'
      return
    }
    report.value = res.data ?? null
    ranMode.value = mode.value
  } catch (e) {
    report.value = null
    error.value = e instanceof Error ? e.message : '评测运行失败'
  } finally {
    running.value = false
  }
}
</script>

<style scoped>
.eval-page { min-height: 100vh; background: var(--color-background); }
.main-content { margin-left: 240px; padding: 24px 32px; color: var(--color-on-surface); }
.hero-title { margin: 0; font-size: 24px; }
.hero-subtitle { margin: 4px 0 20px; color: var(--color-muted); font-size: 14px; }
.notice-zone, .error-zone { border-radius: var(--radius-md); padding: 14px 16px; margin-bottom: 16px; }
.notice-zone { background: var(--color-surface-variant); }
.notice-title { font-weight: 600; margin-bottom: 6px; }
.notice-list { margin: 0; padding-left: 18px; font-size: 13px; color: var(--color-muted); line-height: 1.7; }
.error-zone { background: var(--color-light-red); color: var(--color-error); display: flex; align-items: center; gap: 8px; }
.error-text { flex: 1; }
.table-card { background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 16px; margin-bottom: 16px; }
.card-title { margin: 0 0 12px; font-size: 16px; }
.control-row { display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: 4px; }
.field-label { font-size: 12px; color: var(--color-muted); }
.field-input { padding: 8px 10px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); }
.primary-btn, .action-btn { padding: 8px 14px; border-radius: var(--radius-sm); border: 1px solid var(--color-primary); cursor: pointer; }
.primary-btn { background: var(--color-primary); color: var(--color-on-primary); }
.action-btn { background: transparent; color: var(--color-primary); }
.primary-btn:disabled, .action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.muted { color: var(--color-muted); font-size: 13px; }
.kv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; }
.kv { display: flex; justify-content: space-between; gap: 10px; border-bottom: 1px dashed var(--color-border); padding-bottom: 6px; font-size: 13px; }
.k { color: var(--color-muted); }
.v.mono, .mono { font-family: var(--font-mono, ui-monospace, monospace); }
.pass { color: var(--color-success); }
.fail { color: var(--color-error); }
.case-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.case-table th, .case-table td { text-align: left; padding: 8px; border-bottom: 1px solid var(--color-border); vertical-align: top; }
.case-table th { color: var(--color-muted); font-weight: 500; }
.tag { display: inline-block; padding: 2px 8px; border-radius: var(--radius-sm); font-size: 12px; }
.tag.pass { background: var(--color-primary-light); }
.tag.fail { background: var(--color-warning-light); color: var(--color-warning-dark); }
.kw { max-width: 220px; word-break: break-all; }
.err { color: var(--color-error); max-width: 240px; word-break: break-all; }
code { background: var(--color-surface-variant); padding: 0 4px; border-radius: var(--radius-sm); }
</style>
