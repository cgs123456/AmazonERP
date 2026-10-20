<script setup lang="ts">
/**
 * 财务页的写操作二次确认弹窗。
 *
 * **为什么单独抽一个组件**：`confirm()` 被 5 个 tab 调用（回款重建、结算同步、
 * 差异驳回、索赔驳回、VAT 月结），但弹窗 markup 在原文里只存在于 tax 那段模板中。
 * 留在一个 tab 的模板里，等于让「回款模块能不能弹确认框」取决于 VAT 那段代码
 * 还在不在——搬出来之后 5 个调用点的入口才和弹窗本体同层。
 *
 * 状态（`confirmBox` ref 与 `runConfirm`）**不搬**，留在 Finance.vue：
 * `box.run` 是各 tab 自己的闭包，弹窗只负责渲染与转发两个事件。
 *
 * 样式从 Finance.vue 的 `<style scoped>` 抄来——scoped 样式不跨组件边界，
 * 搬出 markup 不带上类定义，按钮会渲染成无样式（症状看起来像逻辑 bug）。
 * 照 `MasterCsvImport.vue` 的先例在本组件内重新声明。
 */
const props = defineProps<{
  box: { title: string; detail: string } | null
  busy: boolean
  writeAllowed: boolean
}>()

const emit = defineEmits<{
  cancel: []
  confirm: []
}>()
</script>

<template>
  <div v-if="props.box" class="modal-mask" @click.self="emit('cancel')">
    <div class="modal" role="dialog" aria-modal="true">
      <h3>{{ props.box.title }}</h3>
      <p class="confirm-detail">{{ props.box.detail }}</p>
      <div class="modal-actions">
        <button class="filter-btn" @click="emit('cancel')">取消</button>
        <button class="filter-btn" :disabled="props.busy || !props.writeAllowed" @click="emit('confirm')">
          确认执行
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
}
.modal {
  background: var(--color-surface);
  border-radius: var(--radius-md);
  padding: 1.5rem;
  width: 90%;
  max-width: 560px;
  max-height: 90vh;
  overflow-y: auto;
}
.modal h3 {
  margin: 0 0 1rem;
  font-size: 1.125rem;
  color: var(--color-on-surface);
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 1rem;
}
.confirm-detail {
  font-size: 0.875rem;
  color: var(--color-on-surface);
  line-height: 1.5;
  margin: 0;
}
.filter-btn {
  padding: 0.5rem 1rem;
  background: var(--color-primary);
  color: var(--color-on-primary);
  border: none;
  border-radius: var(--radius-md);
  font-size: 0.875rem;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.2s;
}
.filter-btn:hover {
  background: var(--color-primary-dark);
}
</style>
