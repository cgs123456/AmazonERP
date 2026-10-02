<template>
  <div class="profile-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">个人资料</h1>
        <p class="hero-subtitle">查看并修改当前登录账号的昵称、生日与地址</p>
      </div>

      <div v-if="error" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="18" />
        <span class="error-text">{{ error }}</span>
        <button class="action-btn" @click="loadAll">重试</button>
      </div>

      <div class="notice-zone" role="note">
        <div class="notice-title">这一页能改什么、不能改什么</div>
        <ul class="notice-list">
          <li>可改：昵称（≤50 字）、生日（yyyy-MM-dd，后端按它算年龄）、地址（≤200 字）。
            留空提交等于显式清空该字段，后端不会替你把空串当成「不改」。</li>
          <li>不可改：手机号。改绑需要短信验证流程，后端没有对应端点；这里只读展示。</li>
          <li>不可改：头像。写头像的 POST /user/updateImage 会把文件推到阿里云 OSS，
            而 application.yml 里的 accessKeyId/bucketName 仍是 your-access-key-id / your-bucket-name
            占位值，OssUtil 没有凭据检查也没有本地兜底，接进来只会稳定报错。</li>
          <li>不提供：个性签名、学校、证件号。它们在 UserEditDto 里还存在，
            但 amz_user 表已删列，提交了也不会保存——与其给一个填了没反应的输入框，不如说明。</li>
          <li>性别同样不提供：DTO 是 String，库里却是 TINYINT 且全项目没有任何地方解释它的含义；
            后端现在会拒绝非数字取值，但 UI 不该让人去填一个没有语义的编号。</li>
        </ul>
      </div>

      <div v-if="loading" class="skeleton-zone" role="status" aria-label="内容加载中">
        <div class="table-card skeleton-card">
          <div v-for="i in 4" :key="i" class="skeleton sk-line sk-line-sm"></div>
        </div>
        <div class="table-card skeleton-card">
          <div v-for="i in 3" :key="i" class="skeleton sk-line sk-line-lg"></div>
        </div>
      </div>

      <template v-else-if="user">
        <div class="table-card read-card">
          <h2 class="card-title">账号信息（只读）</h2>
          <div class="field-grid">
            <div class="kv"><span class="k">用户 ID</span><span class="v mono">{{ text(user.id) }}</span></div>
            <div class="kv"><span class="k">手机号</span><span class="v mono">{{ maskedPhone }}</span></div>
            <div class="kv"><span class="k">角色</span><span class="v">{{ text(user.role) }}</span></div>
            <div class="kv"><span class="k">年龄</span><span class="v">{{ ageText }}</span></div>
            <div class="kv"><span class="k">性别编号</span><span class="v mono">{{ text(user.sex) }}</span></div>
            <div class="kv"><span class="k">头像地址</span><span class="v mono cell-clip">{{ text(user.image) }}</span></div>
          </div>
          <p class="muted hint">
            头像地址按文本显示：它由外部存储签发，页面不去加载它，避免把访问者泄露给第三方域名。
          </p>
        </div>

        <div class="table-card form-card">
          <h2 class="card-title">可编辑资料（PUT /user/editInfo）</h2>
          <div class="form-grid">
            <label class="field">昵称
              <input v-model="form.nickname" type="text" :maxlength="PROFILE_LIMITS.nickname" />
            </label>
            <label class="field">生日
              <input v-model="form.birthday" type="date" />
            </label>
            <label class="field">地址
              <input v-model="form.address" type="text" :maxlength="PROFILE_LIMITS.address" />
            </label>
          </div>
          <div class="form-actions">
            <button class="action-btn primary" :disabled="busy || !dirty" @click="save">保存修改</button>
            <button class="action-btn" :disabled="busy || !dirty" @click="resetForm">放弃修改</button>
            <span class="muted">{{ dirty ? `将提交 ${changedFields.length} 个字段：${changedFields.join('、')}` : '没有改动' }}</span>
          </div>
          <div v-if="savedTip" class="saved-tip" role="status">{{ savedTip }}</div>
        </div>
      </template>

      <div v-else class="shop-tip">
        未取到登录用户信息（GET /user/getInfo 没有返回 user），本页不发起任何写请求。
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { getUserInfo } from '@/api/auth'
import type { UserVo } from '@/api/auth'
import { PROFILE_LIMITS, editUserInfo } from '@/api/profile'
import { setUserRole } from '@/utils/identity'

const loading = ref(true)
const busy = ref(false)
const error = ref('')
const savedTip = ref('')

const user = ref<UserVo | null>(null)
const age = ref<number | null>(null)
const loaded = reactive<{ nickname: string; birthday: string; address: string }>({
  nickname: '', birthday: '', address: ''
})
const form = reactive<{ nickname: string; birthday: string; address: string }>({
  nickname: '', birthday: '', address: ''
})

const text = (v: unknown): string => (v === null || v === undefined || v === '' ? '-' : String(v))

/** 手机号是个人信息，默认打码；这里没有「显示完整号码」的按钮，因为页面本来就不需要它。 */
const maskedPhone = computed(() => {
  const p = user.value?.phone || ''
  if (p.length < 7) return text(p)
  return p.slice(0, 3) + '****' + p.slice(-4)
})

const ageText = computed(() => (age.value === null || age.value === 0 ? '—（后端按 birthday 计算，格式不符时为 0）' : String(age.value)))

const EDITABLE = ['nickname', 'birthday', 'address'] as const
type EditableField = typeof EDITABLE[number]
const FIELD_LABELS: Record<EditableField, string> = { nickname: '昵称', birthday: '生日', address: '地址' }

const changedFields = computed(() =>
  EDITABLE.filter((k) => form[k] !== loaded[k]).map((k) => FIELD_LABELS[k]))

const dirty = computed(() => changedFields.value.length > 0)

const applyUser = (u: UserVo) => {
  user.value = u
  loaded.nickname = u.nickname ?? ''
  loaded.birthday = u.birthday ?? ''
  loaded.address = u.address ?? ''
  resetForm()
}

const resetForm = () => {
  form.nickname = loaded.nickname
  form.birthday = loaded.birthday
  form.address = loaded.address
  savedTip.value = ''
}

const loadAll = async () => {
  error.value = ''
  loading.value = true
  try {
    const res = await getUserInfo()
    if (res?.code === 200 && res.data?.user) {
      applyUser(res.data.user)
      age.value = typeof res.data.age === 'number' ? res.data.age : null
      // 角色顺带刷新到 localStorage（AppHeader 是主来源，这里保证硬刷进本页时也有值）
      setUserRole(res.data.user.role)
    } else {
      error.value = `无法读取账号信息：${res?.message || 'GET /user/getInfo 返回异常'}`
    }
  } catch (e) {
    error.value = e instanceof Error && e.message ? e.message : '无法读取账号信息'
  } finally {
    loading.value = false
  }
}

const save = async () => {
  if (!dirty.value) return
  busy.value = true
  error.value = ''
  savedTip.value = ''
  const payload: Record<string, string> = {}
  EDITABLE.forEach((key) => {
    if (form[key] !== loaded[key]) payload[key] = form[key].trim() === '' ? '' : form[key].trim()
  })
  try {
    const res = await editUserInfo(payload)
    if (res?.code === 200) {
      const before = user.value ? { ...user.value } : {}
      const merged: UserVo = { ...before, nickname: payload.nickname ?? before.nickname,
        birthday: payload.birthday ?? before.birthday, address: payload.address ?? before.address }
      applyUser(merged)
      savedTip.value = `已保存 ${Object.keys(payload).length} 个字段`
    } else {
      error.value = `保存失败：${res?.message || '后端未返回成功'}`
    }
  } catch (e) {
    error.value = e instanceof Error && e.message ? `保存失败：${e.message}` : '保存失败'
  } finally {
    busy.value = false
  }
}

onMounted(loadAll)
</script>

<style scoped>
.profile-page { background: var(--color-background); }
.skeleton-zone { display: flex; flex-direction: column; gap: 1rem; }
.skeleton-card { padding: 1rem; display: flex; flex-direction: column; gap: 0.625rem; }
.sk-line { height: 0.875rem; }
.sk-line-sm { width: 40%; }
.sk-line-lg { width: 80%; }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.error-text { line-height: 1.5; }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.notice-title { font-weight: 600; margin-bottom: 0.25rem; }
.notice-list { margin: 0; padding-left: 1.125rem; }
.read-card, .form-card { padding: 0.875rem 1rem 1rem; margin-bottom: 1rem; }
.card-title { font-size: 0.9375rem; color: var(--color-on-surface); margin: 0 0 0.75rem; }
.field-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(14rem, 1fr)); gap: 0.625rem; }
.kv { display: flex; flex-direction: column; gap: 0.125rem; background: var(--color-surface-variant); border-radius: var(--radius-md); padding: 0.5rem 0.625rem; }
.k { font-size: 0.75rem; color: var(--color-muted); }
.v { font-size: 0.875rem; color: var(--color-on-surface); word-break: break-all; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(13rem, 1fr)); gap: 0.625rem; }
.field { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.75rem; color: var(--color-muted); }
.field input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.form-actions { display: flex; align-items: center; gap: 0.625rem; margin-top: 0.75rem; flex-wrap: wrap; }
.action-btn { padding: 0.3rem 0.7rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.8125rem; }
.action-btn.primary { background: var(--color-primary); color: var(--color-on-primary); }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.muted { color: var(--color-muted); font-size: 0.75rem; line-height: 1.5; }
.hint { margin: 0.625rem 0 0; }
.saved-tip { margin-top: 0.625rem; color: var(--color-success); font-size: 0.8125rem; }
.cell-clip { max-width: 22rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
