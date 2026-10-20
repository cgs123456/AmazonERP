<script setup lang="ts">
import type { CursorList } from '@/composables/useCursorList'

/**
 * 游标分页条。与凭证列表同规则：是否还有下一页由服务端 `_page` 说了算。
 *
 * 走 props 回调而不是 emit，是为了让「加载更多」始终调用该页自己的 loader，
 * 避免多个列表共用一个全局游标。
 *
 * 原来是 Finance.vue 里用 `defineComponent` + `h()` 写的渲染函数，搬出来时
 * 改成模板：这里没有动态结构，模板更短也更符合本仓库其余组件的写法。
 * 类名一个不改（`page-info` / `page-btn` / `truncated-tip` 都是测试与 E2E
 * 的选择器），样式照 MasterCsvImport.vue 的先例在本组件内重新声明——
 * scoped style 不跨组件边界。
 */
const props = defineProps<{
  state: CursorList<unknown>
  load: (append: boolean) => void
}>()
</script>

<template>
  <div>
    <div class="pagination">
      <span class="page-info">
        已加载 {{ props.state.rows.value.length }} 条{{ props.state.truncated.value ? '，仍有更多未加载' : '' }}
      </span>
      <div class="page-actions">
        <button
          v-if="props.state.truncated.value"
          class="page-btn"
          :disabled="props.state.loading.value"
          @click="props.load(true)"
        >
          {{ props.state.loading.value ? '加载中...' : '加载更多' }}
        </button>
      </div>
    </div>
    <div v-if="props.state.truncated.value" class="truncated-tip" role="status">
      结果已被服务端截断：当前列表不是全量。对账、导出或金额汇总请继续翻页取完。
    </div>
  </div>
</template>

<style scoped>
/* 从 Finance.vue 的 <style scoped> 抄来：scoped 样式不跨组件边界，
   搬出 markup 就必须带上它依赖的类，否则按钮会渲染成无样式——
   症状看起来像逻辑 bug，实际是 CSS。 */
.truncated-tip {
  margin-top: 0.75rem;
  padding: 0.625rem 0.875rem;
  border-radius: var(--amz-radius-md);
  background: var(--color-warning-light);
  color: var(--color-warning-dark);
  font-size: 0.8125rem;
  line-height: 1.6;
}
</style>
