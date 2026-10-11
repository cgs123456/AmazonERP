<template>
  <div class="users-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">用户管理</h1>
        <p class="hero-subtitle">账号列表 · 角色分配 · 启用禁用（仅 ADMIN 可见）</p>
      </div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        三条边界：① 角色只支持 ADMIN/OPERATOR/VIEWER，且<b>不能修改自己的角色</b>
        （防止最后一个 ADMIN 自降后系统无人管理）；
        ② <b>禁用立即拦登录</b>，但已签发 token 在过期前（默认 24 小时）仍然有效
        ——这是「鉴权路径不查库」的既有架构取舍，需要立即踢出时改密码并等 token 过期；
        ③ 新角色/禁用状态对被改用户生效需其<b>重新登录</b>（JWT 24 小时内不刷新 claim）。
      </div>

      <div class="table-card">
        <div class="filter-row">
          <button class="action-btn" :disabled="loading" @click="load">刷新</button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th>ID</th><th>手机号</th><th>昵称</th><th>角色</th><th>状态</th><th>创建时间</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in users" :key="u.id">
              <td class="mono">{{ u.id }}</td>
              <td class="mono">{{ u.phone || '—' }}</td>
              <td>{{ u.nickname || '—' }}</td>
              <td>
                <select
                  class="role-select"
                  :value="u.role"
                  :disabled="isSelf(u) || busyId === u.id"
                  :title="isSelf(u) ? '不能修改自己的角色' : '修改角色'"
                  @change="changeRole(u, ($event.target as HTMLSelectElement).value)"
                >
                  <option value="ADMIN">ADMIN</option>
                  <option value="OPERATOR">OPERATOR</option>
                  <option value="VIEWER">VIEWER</option>
                </select>
              </td>
              <td>
                <span class="status-tag" :class="u.status === 0 ? 'refunded' : 'completed'">
                  {{ u.status === 0 ? '已禁用' : '正常' }}
                </span>
              </td>
              <td class="mono">{{ (u.createTime || '').slice(0, 19).replace('T', ' ') || '—' }}</td>
              <td>
                <button
                  class="row-btn"
                  :disabled="isSelf(u) || busyId === u.id"
                  :title="isSelf(u) ? '不能禁用自己的账号' : ''"
                  @click="toggleStatus(u)"
                >
                  {{ u.status === 0 ? '启用' : '禁用' }}
                </button>
              </td>
            </tr>
            <tr v-if="!loading && !users.length">
              <td colspan="7" class="empty-row">无用户数据（或当前账号无 ADMIN 权限）</td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { listUsers, updateUserRole, updateUserStatus, type UserRow } from '@/api/userAdmin'
// 登录后 user_id 写在 localStorage（main.ts 初始化、登录流程写回），用于「不能操作自己」判断
const currentUserId = localStorage.getItem('user_id')

const users = ref<UserRow[]>([])
const loading = ref(false)
const busyId = ref<number | null>(null)
const errors = ref<string[]>([])

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

const isSelf = (u: UserRow): boolean =>
  currentUserId != null && String(u.id) === String(currentUserId)

const load = async () => {
  loading.value = true
  try {
    const res = await listUsers()
    if (res?.code === 200 && Array.isArray(res.data)) {
      users.value = res.data
      errors.value = []
    } else {
      pushError(`加载用户列表：${res?.message || '后端返回非 200'}`)
      users.value = []
    }
  } catch (e) {
    pushError(`加载用户列表：${e instanceof Error ? e.message : '调用失败'}`)
    users.value = []
  } finally {
    loading.value = false
  }
}

const changeRole = async (u: UserRow, role: string) => {
  if (!u.id) return
  busyId.value = u.id
  try {
    const res = await updateUserRole(u.id, role)
    if (res?.code === 200) {
      u.role = role
    } else {
      pushError(`修改角色：${res?.message || '后端返回非 200'}`)
      await load()
    }
  } catch (e) {
    pushError(`修改角色：${e instanceof Error ? e.message : '调用失败'}`)
    await load()
  } finally {
    busyId.value = null
  }
}

const toggleStatus = async (u: UserRow) => {
  if (!u.id) return
  busyId.value = u.id
  try {
    const next = u.status === 0 ? 1 : 0
    const res = await updateUserStatus(u.id, next)
    if (res?.code === 200) {
      u.status = next
    } else {
      pushError(`修改状态：${res?.message || '后端返回非 200'}`)
      await load()
    }
  } catch (e) {
    pushError(`修改状态：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    busyId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.users-page { background: var(--color-background); }
.error-zone { display: flex; align-items: center; gap: 0.5rem; border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; background: var(--color-light-red); color: var(--color-error); }
.filter-row { display: flex; gap: 0.75rem; align-items: center; padding: 0.75rem 1rem; }
.action-btn { padding: 0.375rem 0.875rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); font-size: 0.8125rem; cursor: pointer; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.role-select { padding: 0.25rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.row-btn { padding: 0.25rem 0.625rem; font-size: 0.8125rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; }
.row-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.status-tag.completed { background: var(--color-success-light); color: var(--color-success); }
.status-tag.refunded { background: var(--color-light-red); color: var(--color-error); }
</style>
