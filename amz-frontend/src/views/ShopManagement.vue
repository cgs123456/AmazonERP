<template>
  <div class="shops-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">店铺管理</h1>
        <p class="hero-subtitle">店铺主数据 · 授权状态 · 用户授权（仅 ADMIN 可见）</p>
      </div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        三条边界：① <b>凭证不在本页维护</b>——店铺的 SP-API 凭证唯一入口是
        <b>连接器中心</b>（/spapi/credentials，加密落库）；本页只管理店铺主数据与授权状态，
        提交时若带凭证字段后端会直接拒绝（避免同一份凭证两个写入源）。
        ② 「授权状态」是店铺级标记（已授权/未授权/授权过期），<b>不等于</b>「凭证已配置」——
        后者看「凭证」列，未配置时应去连接器中心登记。
        ③ 分配/回收店铺后，受影响用户需<b>重新登录</b>才能看到新店铺（JWT 的 shops claim
        有效期默认 24 小时，鉴权路径不查库的既有取舍）。
      </div>

      <div class="table-card" data-panel="shops">
        <div class="filter-row">
          <button class="action-btn" :disabled="loading" @click="load">刷新</button>
          <button class="action-btn" @click="openDialog()">+ 新建店铺</button>
        </div>
        <table class="data-table">
          <thead>
            <tr>
              <th>ID</th><th>店铺名称</th><th>Marketplace</th><th>区域</th>
              <th>Seller ID</th><th>授权状态</th><th>凭证</th><th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in shops" :key="s.id">
              <td class="mono">{{ s.id }}</td>
              <td>{{ s.shopName || '—' }}</td>
              <td class="mono">{{ s.marketplaceId || '—' }}</td>
              <td>{{ s.region || '—' }}</td>
              <td class="mono">{{ s.sellerId || '—' }}</td>
              <td>
                <select
                  class="status-select"
                  :value="String(s.status ?? 0)"
                  :disabled="busy"
                  @change="changeStatus(s, Number(($event.target as HTMLSelectElement).value))"
                >
                  <option value="1">已授权</option>
                  <option value="0">未授权</option>
                  <option value="-1">授权过期</option>
                </select>
              </td>
              <td>
                <span class="status-tag" :class="s.credentialConfigured ? 'healthy' : 'unknown'">
                  {{ s.credentialConfigured ? '已配置' : '未配置' }}
                </span>
              </td>
              <td class="row-actions">
                <button class="action-btn" :disabled="busy" @click="openDialog(s)">编辑</button>
                <button class="action-btn primary" :disabled="busy" @click="openUsers(s)">授权用户</button>
              </td>
            </tr>
            <tr v-if="!loading && !shops.length">
              <td colspan="8" class="empty-row">暂无店铺（或当前账号无 ADMIN 权限）</td>
            </tr>
          </tbody>
        </table>
      </div>
    </main>

    <!-- 新建 / 编辑弹窗 -->
    <div v-if="dialog" class="modal-mask" @click.self="dialog = null">
      <div class="modal" role="dialog" aria-modal="true" data-panel="shop-save">
        <h3>{{ dialog.id ? '编辑店铺' : '新建店铺' }}</h3>
        <div class="form-grid">
          <label>店铺名称 *<input v-model="dialog.shopName" maxlength="100" /></label>
          <label>Marketplace ID *<input v-model="dialog.marketplaceId" placeholder="如 ATVPDKIKX0DER" /></label>
          <label>区域
            <select v-model="dialog.region">
              <option value="">（不指定）</option>
              <option value="NA">NA（北美）</option>
              <option value="EU">EU（欧洲）</option>
              <option value="FE">FE（远东）</option>
            </select>
          </label>
          <label>Seller ID<input v-model="dialog.sellerId" /></label>
        </div>
        <p class="form-hint">凭证不在此处填写：SP-API 凭证的唯一入口是连接器中心。</p>
        <p v-if="dialogError" class="error-zone" role="alert">{{ dialogError }}</p>
        <div class="modal-actions">
          <button class="action-btn cancel" @click="dialog = null">取消</button>
          <button class="action-btn" :disabled="busy || !canSubmit" @click="submitDialog()">保存</button>
        </div>
      </div>
    </div>

    <!-- 授权用户弹窗 -->
    <div v-if="usersDialog" class="modal-mask" @click.self="closeUsers()">
      <div class="modal" role="dialog" aria-modal="true" data-panel="shop-users">
        <h3>「{{ usersDialog.shopName }}」已授权用户</h3>

        <div class="assign-row">
          <label>用户 ID<input data-testid="assign-user-id" v-model="assignUserId" placeholder="用户 ID" /></label>
          <label>店铺角色
            <select v-model="assignRole">
              <option value="ADMIN">ADMIN</option>
              <option value="OPERATOR">OPERATOR</option>
              <option value="VIEWER">VIEWER</option>
            </select>
          </label>
          <button class="action-btn" :disabled="busy || !assignUserId.trim()" @click="doAssign()">分配</button>
        </div>

        <p v-if="usersError" class="error-zone" role="alert">{{ usersError }}</p>

        <table class="data-table">
          <thead>
            <tr><th>用户 ID</th><th>昵称</th><th>手机号</th><th>店铺角色</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="u in shopUsers" :key="u.userId">
              <td class="mono">{{ u.userId }}</td>
              <td>{{ u.nickname || '—' }}</td>
              <td class="mono">{{ u.phone || '—' }}</td>
              <td>{{ u.role || '—' }}</td>
              <td>
                <button class="action-btn cancel" :disabled="busy" @click="confirmUnassign(u)">解绑</button>
              </td>
            </tr>
            <tr v-if="!usersLoading && !shopUsers.length">
              <td colspan="5" class="empty-row">该店铺暂无授权用户</td>
            </tr>
          </tbody>
        </table>
        <p class="form-hint">分配/回收后，受影响用户需重新登录才能看到新店铺（JWT 24 小时内不刷新）。</p>

        <div class="modal-actions">
          <button class="action-btn" @click="closeUsers()">关闭</button>
        </div>
      </div>
    </div>

    <div v-if="confirmBox" class="modal-mask" @click.self="confirmBox = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ confirmBox.title }}</h3>
        <p class="confirm-detail">{{ confirmBox.detail }}</p>
        <div class="modal-actions">
          <button class="action-btn cancel" @click="confirmBox = null">再想想</button>
          <button class="action-btn" :disabled="busy" @click="runConfirm()">确认执行</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import {
  getMyShops, createShop, updateShop, updateShopStatus,
  listShopUsers, assignShopUser, unassignShopUser,
  type ShopListItem, type ShopUserItem
} from '@/api/auth'

const shops = ref<ShopListItem[]>([])
const loading = ref(false)
const busy = ref(false)
const errors = ref<string[]>([])

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}
const clearErrorsByPrefix = (prefix: string) => {
  errors.value = errors.value.filter((x) => !x.startsWith(prefix))
}

const load = async () => {
  loading.value = true
  try {
    const res = await getMyShops()
    if (res?.code === 200 && Array.isArray(res.data)) {
      shops.value = res.data
      clearErrorsByPrefix('店铺列表：')
    } else {
      pushError(`店铺列表：${res?.message || '后端返回非 200'}`)
      shops.value = []
    }
  } catch (e) {
    pushError(`店铺列表：${e instanceof Error ? e.message : '调用失败'}`)
    shops.value = []
  } finally {
    loading.value = false
  }
}

/* ---------- 新建 / 编辑 ---------- */

interface ShopDialog {
  id?: number
  shopName: string
  marketplaceId: string
  region: string
  sellerId: string
}

const dialog = ref<ShopDialog | null>(null)
const dialogError = ref('')

const openDialog = (s?: ShopListItem) => {
  dialogError.value = ''
  dialog.value = s
    ? {
        id: s.id as number,
        shopName: s.shopName || '',
        marketplaceId: s.marketplaceId || '',
        region: s.region || '',
        sellerId: s.sellerId || ''
      }
    : { shopName: '', marketplaceId: '', region: '', sellerId: '' }
}

/** 与后端 validateShop 同口径前置校验：名称与 marketplaceId 必填。 */
const canSubmit = computed(() => {
  const d = dialog.value
  if (!d) return false
  return d.shopName.trim() !== '' && d.marketplaceId.trim() !== ''
})

const submitDialog = async () => {
  const d = dialog.value
  if (!d) return
  busy.value = true
  dialogError.value = ''
  try {
    const payload = {
      shopName: d.shopName.trim(),
      marketplaceId: d.marketplaceId.trim(),
      region: d.region.trim() || undefined,
      sellerId: d.sellerId.trim() || undefined
    }
    const res = d.id ? await updateShop(d.id, payload) : await createShop(payload)
    if (res?.code !== 200) {
      dialogError.value = `保存失败：${res?.message || '后端返回非 200'}`
      return
    }
    dialog.value = null
    await load()
  } catch (e) {
    dialogError.value = `保存失败：${e instanceof Error ? e.message : '调用失败'}`
  } finally {
    busy.value = false
  }
}

/* ---------- 授权状态 ---------- */

const changeStatus = async (s: ShopListItem, status: number) => {
  if (!s.id) return
  busy.value = true
  try {
    const res = await updateShopStatus(s.id, status)
    if (res?.code === 200) {
      s.status = status
      clearErrorsByPrefix('修改状态：')
    } else {
      pushError(`修改状态：${res?.message || '后端返回非 200'}`)
      await load()
    }
  } catch (e) {
    pushError(`修改状态：${e instanceof Error ? e.message : '调用失败'}`)
    await load()
  } finally {
    busy.value = false
  }
}

/* ---------- 授权用户 ---------- */

const usersDialog = ref<null | { shopId: number; shopName: string }>(null)
const shopUsers = ref<ShopUserItem[]>([])
const usersLoading = ref(false)
const usersError = ref('')
const assignUserId = ref('')
const assignRole = ref('VIEWER')

const loadShopUsers = async () => {
  const d = usersDialog.value
  if (!d) return
  usersLoading.value = true
  try {
    const res = await listShopUsers(d.shopId)
    if (res?.code === 200 && Array.isArray(res.data)) {
      shopUsers.value = res.data
      usersError.value = ''
    } else {
      usersError.value = `绑定用户列表：${res?.message || '后端返回非 200'}`
      shopUsers.value = []
    }
  } catch (e) {
    usersError.value = `绑定用户列表：${e instanceof Error ? e.message : '调用失败'}`
    shopUsers.value = []
  } finally {
    usersLoading.value = false
  }
}

const openUsers = async (s: ShopListItem) => {
  if (!s.id) return
  usersError.value = ''
  assignUserId.value = ''
  assignRole.value = 'VIEWER'
  usersDialog.value = { shopId: Number(s.id), shopName: s.shopName || String(s.id) }
  await loadShopUsers()
}

const closeUsers = () => {
  usersDialog.value = null
  shopUsers.value = []
  usersError.value = ''
}

const doAssign = async () => {
  const d = usersDialog.value
  const uid = Number(assignUserId.value)
  if (!d || !Number.isFinite(uid) || uid <= 0) {
    usersError.value = '用户 ID 必须是正整数'
    return
  }
  busy.value = true
  try {
    const res = await assignShopUser(d.shopId, uid, assignRole.value)
    if (res?.code !== 200) {
      usersError.value = `分配失败：${res?.message || '后端返回非 200'}`
      return
    }
    usersError.value = ''
    assignUserId.value = ''
    await loadShopUsers()
  } catch (e) {
    usersError.value = `分配失败：${e instanceof Error ? e.message : '调用失败'}`
  } finally {
    busy.value = false
  }
}

const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

const confirmUnassign = (u: ShopUserItem) => {
  const d = usersDialog.value
  if (!d) return
  confirmBox.value = {
    title: '解除店铺授权',
    detail: `回收用户 ${u.nickname || u.userId} 对「${d.shopName}」的授权。`
      + '该用户需重新登录后才会失去这家店（JWT 24 小时内不刷新 claim）。',
    run: async () => {
      busy.value = true
      try {
        const res = await unassignShopUser(d.shopId, u.userId)
        if (res?.code !== 200) {
          usersError.value = `解绑失败：${res?.message || '后端返回非 200'}`
          return
        }
        usersError.value = ''
        await loadShopUsers()
      } catch (e) {
        usersError.value = `解绑失败：${e instanceof Error ? e.message : '调用失败'}`
      } finally {
        busy.value = false
      }
    }
  }
}

const runConfirm = async () => {
  const box = confirmBox.value
  confirmBox.value = null
  if (!box) return
  await box.run()
}

onMounted(() => { void load() })
</script>

<style scoped>
.shops-page { background: var(--color-background); }
.main-content { padding: 1.5rem; }
.hero-section { margin-bottom: 1rem; }
.hero-title { font-size: 1.5rem; font-weight: 700; margin: 0; }
.hero-subtitle { font-size: 0.875rem; color: var(--color-muted); margin: 0.25rem 0 0; }
.notice-zone, .error-zone { padding: 0.75rem 1rem; border-radius: var(--radius-md); margin-bottom: 1rem; font-size: 0.8125rem; line-height: 1.6; }
.error-zone { background: var(--color-light-red); color: var(--color-error); display: flex; gap: 0.5rem; align-items: center; }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); }
.table-card { background: var(--color-surface); border-radius: var(--radius-md); padding: 1rem; }
.filter-row { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; }
.data-table { width: 100%; border-collapse: collapse; font-size: 0.8125rem; }
.data-table th, .data-table td { padding: 0.5rem; border-bottom: 1px solid var(--color-border); text-align: left; }
.data-table th { color: var(--color-muted); font-weight: 600; }
.empty-row { text-align: center; color: var(--color-muted); padding: 1.5rem; }
.mono { font-family: ui-monospace, monospace; }
.row-actions { white-space: nowrap; }
.status-select { padding: 0.25rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.status-tag { padding: 0.125rem 0.5rem; border-radius: 999px; font-size: 0.75rem; }
.status-tag.healthy { background: var(--color-primary-light); color: var(--color-primary); }
.status-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); background: var(--color-surface); font-size: 0.75rem; cursor: pointer; }
.action-btn.primary { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.action-btn.cancel { color: var(--color-error); }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.modal-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.5); display: flex; align-items: center; justify-content: center; z-index: 50; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 640px; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 1rem; font-size: 1.125rem; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label, .assign-row label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-muted); }
.form-grid input, .form-grid select, .assign-row input, .assign-row select { padding: 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.8125rem; }
.assign-row { display: flex; align-items: flex-end; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.form-hint { font-size: 0.75rem; color: var(--color-muted); margin: 0.5rem 0; line-height: 1.5; }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.8125rem; line-height: 1.6; color: var(--color-on-surface); }
@media (max-width: 1024px) { .main-content { margin-left: 80px; } }
@media (max-width: 768px) { .main-content { margin-left: 0; padding: 1rem; } }
</style>
