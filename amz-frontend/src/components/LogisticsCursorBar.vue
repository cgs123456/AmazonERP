<script setup lang="ts">
/**
 * 物流看板的游标分页条（「已加载 N 条 / 加载更多 / 完整性提示」）。
 *
 * Why this exists (§S32)：
 *     LogisticsDashboard.vue 里有 4 份几乎逐字相同的这块 markup
 *     （shipments / quotes / transfers / tracking），每份约 30 行。
 *     抽出来后每处只剩一行组件调用。
 *
 * 抽象的理由是**4 个真实用例**已存在（不是预判），且它们的文案与判定口径
 *     完全一致——包括「truncated 与 pageMetaMissing 互斥」这条：
 *     截断是服务端明确说「还有更多」，缺失是服务端没给元数据，
 *     两者的用户可见后果不同（前者可继续加载，后者完整性未知），不能合并成一个布尔。
 *
 * 刻意**不**把 loader 收进来：这 4 个列表各自调不同的 API、失败处理也不同，
 *     收进来只会变成一个大 switch。这里只管「状态怎么显示」与「按钮什么时候可点」。
 */
const props = defineProps<{
  /** 已加载的行数（用于「已加载 N 条」） */
  loaded: number
  /** 服务端明确截断：还有更多，可继续加载 */
  truncated: boolean
  /** 服务端未返回分页元数据：完整性未知，不可视为全量 */
  pageMetaMissing: boolean
  /** 正在加载下一页 */
  loadingMore: boolean
  /** 还有下一页游标 */
  hasNext: boolean
}>()

const emit = defineEmits<{ (e: 'load-more'): void }>()
</script>

<template>
  <div class="pagination">
    <span class="page-info">
      已加载 {{ props.loaded }} 条<template v-if="props.truncated">，仍有更多未加载</template><template v-else-if="props.pageMetaMissing">，完整性未知</template>
    </span>
    <div class="page-actions">
      <button
        v-if="props.truncated"
        class="page-btn"
        :disabled="props.loadingMore || !props.hasNext"
        @click="emit('load-more')"
      >
        {{ props.loadingMore ? '加载中...' : '加载更多' }}
      </button>
    </div>
  </div>
  <div v-if="props.truncated" class="truncated-tip" role="status">
    结果已被服务端截断：当前列表不是全量。请继续加载后再用于对账、导出或汇总。
  </div>
  <div v-else-if="props.pageMetaMissing" class="truncated-tip" role="status">
    服务端未返回分页元数据，列表完整性无法确认：当前列表不能视为全量。
  </div>
</template>
