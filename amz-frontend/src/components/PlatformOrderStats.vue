<template>
  <div class="stats-block" data-panel="platform-stats">
    <div class="stats-head">
      <span class="stats-title">分平台概览</span>
      <button class="action-btn" :disabled="loading" @click="load">重算</button>
      <span class="muted stats-note">
        各平台币种与费用结构不同，这里<b>不做跨平台合计</b>——
        「全店销售额」这种数字必须先定汇率与费用归集口径才有意义
      </span>
    </div>

    <p v-if="error" class="error-zone" role="alert">{{ error }}</p>
    <p v-if="stats?.truncatedPlatforms?.length" class="truncation-note" role="status">
      以下平台数据超出统计上限，下面的数字不是该平台全量：
      {{ stats.truncatedPlatforms.join('、') }}
    </p>

    <div v-if="stats?.platforms?.length" class="stats-grid">
      <div v-for="b in stats.platforms" :key="b.platform" class="stats-card">
        <div class="stats-card-head">
          <strong>{{ b.platform }}</strong>
          <span class="muted">{{ b.orderCount }} 单</span>
        </div>
        <!-- 订单数条：跨平台唯一可比的裸计数（金额因币种不同不可比，故不画金额条）。
             宽度相对该平台集内的最大值，用于「哪个平台订单更多」的直观比较。 -->
        <div class="stats-bar-row" aria-hidden="true">
          <span class="stats-bar" :style="{ width: barWidth(b.orderCount) }"></span>
        </div>
        <div class="stats-row">
          <span class="stats-label">金额</span>
          <span class="stats-value">
            <span v-if="!Object.keys(b.amountByCurrency || {}).length">—</span>
            <span v-for="(amt, cur) in b.amountByCurrency" :key="cur" class="amt-chip">
              {{ amt }} {{ cur }}
            </span>
          </span>
        </div>
        <div class="stats-row">
          <span class="stats-label">状态</span>
          <span class="stats-value">
            <span v-if="!Object.keys(b.statusCounts || {}).length">—</span>
            <span v-for="(cnt, st) in b.statusCounts" :key="st" class="amt-chip">
              {{ st }} {{ cnt }}
            </span>
          </span>
        </div>
        <div v-if="b.truncated" class="stats-truncated">该平台已触统计上限</div>
      </div>
    </div>
    <p v-else-if="!loading" class="muted">该店铺暂无多平台订单，概览为空</p>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useShopGuard } from '@/composables/useShopGuard'
import * as api from '@/api/multiplatform'

/**
 * 分平台订单统计（A8）。第二个渲染用例到达才抽成共享组件：
 * MultiplatformOrders（订单页概览）与 ReportCenter（经营报表新分区）都用它，
 * 渲染体/口径文案（「不做跨平台合计」、触顶点名）此前内联在订单页，重复发生才抽。
 *
 * 自持加载：onMounted 拉一次，「重算」再拉。放进 ReportCenter 的 v-if 面板里时，
 * 挂载即请求——正好满足该页「进入分区才请求对应端点」的懒加载纪律（面板未挂载则不请求）。
 */
const { refreshShop } = useShopGuard()
const stats = ref<api.PlatformOrderStatsDto | null>(null)
const loading = ref(false)
const error = ref('')

const maxOrderCount = computed(() =>
  (stats.value?.platforms ?? []).reduce((m, b) => Math.max(m, b.orderCount || 0), 0))

/** 相对最大值的宽度（百分比）。无数据时给 0，避免除零。 */
const barWidth = (count: number) => {
  const max = maxOrderCount.value
  if (!max) return '0%'
  return `${Math.max(4, Math.round((count / max) * 100))}%`
}

const load = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  loading.value = true
  error.value = ''
  try {
    const res = await api.orderStats(shopId)
    if (res?.code !== 200) {
      error.value = `分平台统计：${res?.message || '接口返回非 200'}`
      stats.value = null
      return
    }
    stats.value = res.data ?? null
  } catch (e) {
    // 「拉不到」与「真的没有订单」必须可区分
    error.value = `分平台统计：${(e as Error)?.message || '调用失败'}`
    stats.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stats-block { margin-bottom: 0.875rem; padding: 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.stats-head { display: flex; align-items: center; gap: 0.5rem; flex-wrap: wrap; margin-bottom: 0.5rem; }
.stats-title { font-weight: 600; font-size: 0.9375rem; }
.stats-note { font-size: 0.75rem; }
.stats-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 0.5rem; }
.stats-card { border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.5rem 0.625rem; }
.stats-card-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 0.375rem; }
.stats-bar-row { height: 6px; background: var(--color-surface-alt, #f0f0f0); border-radius: 3px; margin-bottom: 0.375rem; overflow: hidden; }
.stats-bar { display: block; height: 100%; background: var(--color-primary); border-radius: 3px; transition: width 0.2s; }
.stats-row { display: flex; gap: 0.5rem; font-size: 0.75rem; margin-bottom: 0.25rem; }
.stats-label { color: var(--color-muted); flex: 0 0 2.5rem; }
.stats-value { display: flex; flex-wrap: wrap; gap: 0.25rem; }
.amt-chip { background: var(--color-surface-alt, #f5f5f5); border-radius: 999px; padding: 0 0.375rem; }
.stats-truncated { margin-top: 0.25rem; font-size: 0.7rem; color: var(--color-error, #c62828); }
.truncation-note { font-size: 0.75rem; color: var(--color-error, #c62828); margin-bottom: 0.5rem; }
.error-zone { background: var(--color-error-light, #fdecea); color: var(--color-error, #c62828); padding: 0.5rem 0.75rem; border-radius: var(--radius-md); font-size: 0.8125rem; margin-bottom: 0.5rem; }
</style>
