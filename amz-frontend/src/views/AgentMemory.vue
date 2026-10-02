<template>
  <div class="memory-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">助手记忆</h1>
        <p class="hero-subtitle">Agent 记住的偏好与对话，读写 amz-service-ai 的 user_preference / conversation_memory 真实表</p>
      </div>

      <div v-if="error" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="18" />
        <span class="error-text">{{ error }}</span>
        <button class="action-btn" @click="loadAll">重试</button>
      </div>

      <div v-if="!userId" class="shop-tip">
        未能确认当前登录用户（GET /user/getInfo 没有返回 user.id）。四个记忆端点都按登录身份鉴权，
        因此身份缺失时本页不发起任何请求。
      </div>

      <div class="notice-zone" role="note">
        <div class="notice-title">本页的四条边界</div>
        <ul class="notice-list">
          <li>身份只来自登录态：路径里的 userId 由后端与登录用户比对，普通用户读他人 id 返回 403；
            保存偏好时后端会清空 body 里的 id 并强制改写 userId，前端上报的身份不参与写入归属。</li>
          <li>偏好是「按需更新」：后端走 MyBatis-Plus 非空字段更新，留空的字段不会被清空，
            所以本页不提供「清除偏好」的假按钮。</li>
          <li>首次读取偏好时后端会自动建一条默认记录（偏好店铺 = 1），那不是真实偏好，
            需要你按下面的表单改成本人实际使用的店铺。</li>
          <li>全站右上角的店铺下拉仍是 main.ts 里写死的 3 个示例店铺（后端没有「我的店铺列表」端点），
            所以这里用数字输入而不是那个下拉，避免把示例店铺名当成授权范围。</li>
        </ul>
      </div>

      <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
        <div class="table-card skeleton-card">
          <div v-for="i in 4" :key="i" class="skeleton sk-line sk-line-sm"></div>
        </div>
        <div class="table-card skeleton-card">
          <div v-for="i in 6" :key="i" class="skeleton sk-line" :class="i % 2 ? 'sk-line-lg' : 'sk-line-sm'"></div>
        </div>
      </div>

      <template v-else-if="userId">
        <div class="table-card form-card">
          <h2 class="card-title">用户偏好（user_preference）</h2>
          <div class="meta-row">
            <span class="muted">用户 ID <b class="mono">{{ userId }}</b></span>
            <span class="muted">最近活跃 {{ text(pref?.lastActiveTime) }}</span>
            <span class="muted">记录更新 {{ text(pref?.updateTime) }}</span>
          </div>

          <div class="form-grid">
            <label class="field">昵称
              <input v-model="form.nickname" type="text" maxlength="64" placeholder="提醒与报告里的称呼" />
            </label>
            <label class="field">偏好店铺 ID
              <input v-model="form.preferredShopId" type="number" min="1" placeholder="例如 1" />
            </label>
            <label class="field">偏好品类
              <input v-model="form.preferredCategory" type="text" maxlength="64" placeholder="例如 瑜伽用品" />
            </label>
          </div>

          <div class="form-actions">
            <button class="action-btn primary" :disabled="busy || !userId" @click="savePreference">保存偏好</button>
            <span v-if="savedTip" class="muted">{{ savedTip }}</span>
          </div>

          <div class="lang-row">
            <label class="field">回复语言
              <select v-model="lang">
                <option v-for="l in AGENT_LANGUAGES" :key="l.code" :value="l.code">{{ l.label }}（{{ l.code }}）</option>
              </select>
            </label>
            <button class="action-btn" :disabled="busy || !userId || lang === pref?.language" @click="applyLanguage">
              仅切换语言
            </button>
            <span class="muted">当前生效 {{ text(pref?.language) }}；未识别的语言代码会被后端拒绝，不会静默改回中文。</span>
          </div>
        </div>

        <div class="table-card history-card">
          <div class="filter-row">
            <h2 class="card-title">对话记忆（conversation_memory，会话键 sess-{{ userId }}）</h2>
            <label class="filter">条数
              <select v-model.number="limit" @change="loadHistory">
                <option :value="20">20</option>
                <option :value="50">50</option>
                <option :value="100">100</option>
                <option :value="200">200</option>
              </select>
            </label>
            <button class="action-btn" :disabled="historyLoading" @click="loadHistory">刷新</button>
            <span class="muted">时间正序，共 {{ history.length }} 条</span>
          </div>
          <table class="data-table">
            <thead>
              <tr><th>时间</th><th>角色</th><th>内容</th></tr>
            </thead>
            <tbody>
              <tr v-for="m in history" :key="m.id">
                <td class="mono">{{ m.createTime || '未记录' }}</td>
                <td><span class="status-tag" :class="m.role === 'user' ? 'role-user' : 'role-assistant'">{{ m.role }}</span></td>
                <td class="content-cell">{{ m.content }}</td>
              </tr>
              <tr v-if="!history.length">
                <td colspan="3" class="empty-row">
                  <div class="empty-state">
                    <Icon icon="mdi:chat-remove-outline" width="32" class="empty-icon" />
                    <span>暂无对话记忆</span>
                    <span class="muted">
                      这张表只由 POST /ai/agent/memory/chat 写入（需配置 deepseek.api-key）；
                      仪表盘浮窗的 SSE 与 POST /ai/erp/agent 走 LangChain4j 内存会话，不落库，所以这里为空是正常状态。
                    </span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getUserInfo } from '@/api/auth'
import {
  AGENT_LANGUAGES, getAgentHistory, getAgentPreference, saveAgentPreference, switchAgentLanguage
} from '@/api/agentMemory'
import type { AgentPreference, ConversationMemoryRow } from '@/api/agentMemory'

const userId = ref<number | null>(null)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
const savedTip = ref('')

const pref = ref<AgentPreference | null>(null)
const form = reactive<{ nickname: string; preferredShopId: string; preferredCategory: string }>({
  nickname: '',
  preferredShopId: '',
  preferredCategory: ''
})
const lang = ref<string>('ZH')

const history = ref<ConversationMemoryRow[]>([])
const historyLoading = ref(false)
const limit = ref(50)

// 后端返回 null/undefined 与「未记录」在展示上要区分开，统一成 '-' 会掩盖字段缺失
const text = (v: unknown): string => (v === null || v === undefined || v === '' ? '-' : String(v))

const errText = (e: unknown, fallback: string): string =>
  e instanceof Error && e.message ? e.message : fallback

/**
 * 身份来源：GET /user/getInfo 的 user.id。
 * 不用 localStorage 的 user_id —— 它由 main.ts 在缺失时兜底写成 '1'，
 * 而这里的路径参数会被后端与登录用户比对，用兜底值只会稳定得到 403。
 */
const resolveIdentity = async (): Promise<boolean> => {
  const res = await getUserInfo()
  if (res?.code === 200 && res.data?.user?.id) {
    userId.value = Number(res.data.user.id)
    return true
  }
  error.value = `无法确认登录身份：${res?.message || 'GET /user/getInfo 返回异常'}`
  return false
}

const syncForm = (p: AgentPreference | null) => {
  form.nickname = p?.nickname ?? ''
  form.preferredShopId = p?.preferredShopId != null ? String(p.preferredShopId) : ''
  form.preferredCategory = p?.preferredCategory ?? ''
  lang.value = p?.language ?? 'ZH'
}

const loadPreference = async () => {
  if (!userId.value) return
  try {
    const res = await getAgentPreference(userId.value)
    if (res?.code === 200) {
      pref.value = res.data ?? null
      syncForm(pref.value)
    } else {
      error.value = `偏好读取失败：${res?.message || 'HTTP 业务码非 200'}`
    }
  } catch (e) {
    error.value = errText(e, '偏好读取失败')
  }
}

const loadHistory = async () => {
  if (!userId.value) return
  historyLoading.value = true
  try {
    const res = await getAgentHistory(userId.value, limit.value)
    if (res?.code === 200) {
      history.value = Array.isArray(res.data) ? res.data : []
    } else {
      error.value = `对话记忆读取失败：${res?.message || 'HTTP 业务码非 200'}`
    }
  } catch (e) {
    error.value = errText(e, '对话记忆读取失败')
  } finally {
    historyLoading.value = false
  }
}

const loadAll = async () => {
  error.value = ''
  savedTip.value = ''
  loading.value = true
  const ok = await resolveIdentity()
  if (ok) {
    await Promise.all([loadPreference(), loadHistory()])
  }
  loading.value = false
}

const savePreference = async () => {
  if (!userId.value) return
  busy.value = true
  savedTip.value = ''
  error.value = ''
  const shopId = Number(form.preferredShopId)
  const payload: Parameters<typeof saveAgentPreference>[0] = {}
  if (form.nickname.trim()) payload.nickname = form.nickname.trim()
  if (form.preferredCategory.trim()) payload.preferredCategory = form.preferredCategory.trim()
  if (form.preferredShopId !== '' && Number.isFinite(shopId) && shopId > 0) payload.preferredShopId = shopId
  try {
    const res = await saveAgentPreference(payload)
    if (res?.code === 200) {
      pref.value = res.data ?? pref.value
      syncForm(pref.value)
      savedTip.value = '已保存'
    } else {
      error.value = `偏好保存失败：${res?.message || 'HTTP 业务码非 200'}`
    }
  } catch (e) {
    error.value = errText(e, '偏好保存失败')
  } finally {
    busy.value = false
  }
}

const applyLanguage = async () => {
  if (!userId.value) return
  busy.value = true
  error.value = ''
  savedTip.value = ''
  try {
    const res = await switchAgentLanguage(lang.value)
    if (res?.code === 200) {
      pref.value = res.data ?? pref.value
      syncForm(pref.value)
    } else {
      error.value = `语言切换失败：${res?.message || 'HTTP 业务码非 200'}`
    }
  } catch (e) {
    error.value = errText(e, '语言切换失败')
  } finally {
    busy.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.memory-page { background: var(--color-background); }
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.skeleton-card { padding: 1rem; display: flex; flex-direction: column; gap: 0.625rem; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 40%; }
.sk-line-lg { width: 85%; }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.error-text { line-height: 1.5; }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.notice-title { font-weight: 600; margin-bottom: 0.25rem; }
.notice-list { margin: 0; padding-left: 1.125rem; }
.form-card, .history-card { padding: 0.875rem 1rem 1rem; margin-bottom: 1rem; }
.history-card { padding-top: 0; }
.card-title { font-size: 0.9375rem; color: var(--color-on-surface); margin: 0.75rem 0 0.5rem; }
.form-card .card-title:first-child, .filter-row .card-title { margin-top: 0; }
.meta-row { display: flex; gap: 1rem; flex-wrap: wrap; margin-bottom: 0.75rem; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr)); gap: 0.625rem; }
.field { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.75rem; color: var(--color-muted); }
.field input, .field select { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.form-actions, .lang-row { display: flex; align-items: center; gap: 0.625rem; margin-top: 0.75rem; flex-wrap: wrap; }
.muted { color: var(--color-muted); font-size: 0.75rem; line-height: 1.5; }
.action-btn { padding: 0.3rem 0.7rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.8125rem; }
.action-btn.primary { background: var(--color-primary); color: var(--color-on-primary); }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.875rem 0 0.5rem; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter select { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; white-space: nowrap; }
.status-tag.role-user { background: var(--color-primary-light); color: var(--color-primary); }
.status-tag.role-assistant { background: var(--color-muted-light); color: var(--color-muted); }
.content-cell { max-width: 46rem; white-space: pre-wrap; word-break: break-word; font-size: 0.8125rem; }
.empty-state { display: flex; flex-direction: column; align-items: center; gap: 0.375rem; }
</style>
