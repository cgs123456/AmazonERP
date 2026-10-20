<template>
  <div class="csv-import" data-panel="inventory-csv-import">
    <div class="ci-row">
      <input ref="fileInput" type="file" accept=".csv" class="ci-file" :disabled="!roleAllowed" @change="onFileChange" />
      <button class="page-btn" :disabled="!roleAllowed || !file || uploading" @click="upload">
        {{ uploading ? '导入中…' : '导入 CSV' }}
      </button>
      <span v-if="!roleAllowed" class="ci-locked" role="note">CSV 导入需 OPERATOR 或 ADMIN 角色</span>
    </div>
    <p class="muted ci-hint">
      行定位键：warehouse_id + sku + batch_no（batch_no 列必须在，值留空=匹配无批次的行）；
      要补的元数据：asin / location_code / expire_date，留空则保持原值。<br />
      <b>只更新已存在的库存行，不新建；数量列（quantity 等）不允许批量改</b>——
      库存变更必须走盘点单或出入库单据，要进库存流水账。
    </p>

    <p v-if="error" class="error-zone" role="alert">{{ error }}</p>

    <div v-if="report" class="ci-report" data-panel="inventory-import-report">
      <div class="ci-stats">
        <span>共 {{ report.totalRows }} 行</span>
        <span class="ok">更新 {{ report.updatedRows }}</span>
        <span v-if="report.skippedRows">跳过空行 {{ report.skippedRows }}</span>
        <span v-if="report.failedRows" class="fail">失败 {{ report.failedRows }}</span>
      </div>
      <p class="muted">
        识别到的表头：{{ report.headers.join('、') || '（空）' }}
        ——列名对不上时先核这里，再看行级错误。
      </p>
      <table v-if="report.failedRows" class="data-table ci-errors">
        <thead><tr><th>文件行号</th><th>原因</th></tr></thead>
        <tbody>
          <tr v-for="(reason, line) in report.errors" :key="line">
            <td class="mono">第 {{ line }} 行</td>
            <td>{{ reason }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { importInventoryCsv, type InventoryImportReport } from '@/api/warehouse'

/**
 * 库存元数据 CSV 批量维护面板（A6 第二个域）。自足：自己管文件与报告状态，
 * 成功后 emit imported 让父页面刷新库存列表。
 *
 * 为什么不与商品主数据的 MasterCsvImport 合并成一个共享面板：两个导入契约的
 * 用户可见语义不同——商品是 upsert（会出现「新增 N」），库存是<b>只更新已存在行、
 * 不新建</b>，且库存额外强制「数量列出现即整行拒绝」。合并后共享面板要对
 * insertedRows 与数量提示做条件分支，反而不如两个小兄弟清楚。等出现<b>第三个</b>
 * 真实导入域、能定出稳定接口时再抽（与「抽象延迟到第二个用例、第二个用例在此处被证伪」
 * 的取舍一致）。
 */
const props = withDefaults(defineProps<{ roleAllowed?: boolean }>(), { roleAllowed: true })
const emit = defineEmits<{ (e: 'imported'): void }>()

const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const uploading = ref(false)
const error = ref('')
const report = ref<InventoryImportReport | null>(null)

const onFileChange = () => {
  file.value = fileInput.value?.files?.[0] ?? null
  // 换文件就清掉上一份报告，否则新旧结果混在一起会看错行号
  report.value = null
  error.value = ''
}

const upload = async () => {
  // 与商品主数据导入同一取法（见 MasterCsvImport）：直接读 current_shop_id。
  // 与商品主数据导入同一取法，不引入 useShopGuard：面板在页面挂载后才由用户主动触发，
  // 此刻守卫早已稳定；且它不参与列表加载，直接读 localStorage 足够。
  const shopId = localStorage.getItem('current_shop_id')
  if (!file.value || !shopId) return
  // 与父页面同一口径的兜底：禁用态可能在角色变更前就已渲染，或者角色比面板晚到
  if (!props.roleAllowed) {
    error.value = 'CSV 导入需 OPERATOR 或 ADMIN 角色'
    return
  }
  uploading.value = true
  error.value = ''
  try {
    const res = await importInventoryCsv(shopId, file.value)
    if (res?.code !== 200) {
      // 结构错误（缺列/超行数/文件超限）走这里：整批没导，报告不渲染
      error.value = res?.message || '导入失败'
      report.value = null
      return
    }
    report.value = res.data ?? null
    emit('imported')
  } catch (e) {
    error.value = e instanceof Error ? e.message : '导入失败'
    report.value = null
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
    file.value = null
  }
}
</script>

<style scoped>
.csv-import { margin: 0 0 0.75rem; }
.ci-row { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
.ci-file { font-size: 0.8125rem; }
.ci-locked { color: var(--color-muted); font-size: 0.75rem; }
.ci-hint { font-size: 0.75rem; margin: 0.375rem 0 0; line-height: 1.6; }
.ci-report { margin-top: 0.5rem; padding: 0.625rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--amz-radius-md); }
.ci-stats { display: flex; gap: 0.875rem; font-size: 0.8125rem; flex-wrap: wrap; }
.ci-stats .ok { color: var(--color-primary); }
.ci-stats .fail { color: var(--color-error, #c62828); font-weight: 600; }
.ci-errors { margin-top: 0.5rem; }
.error-zone { background: var(--color-error-light, #fdecea); color: var(--color-error, #c62828); padding: 0.5rem 0.75rem; border-radius: var(--amz-radius-md); font-size: 0.8125rem; margin-top: 0.5rem; }
</style>
