<template>
  <div class="csv-import" data-panel="master-csv-import">
    <div class="ci-row">
      <input ref="fileInput" type="file" accept=".csv" class="ci-file"
             @change="onFileChange" />
      <button class="page-btn" :disabled="!file || uploading" @click="upload">
        {{ uploading ? '导入中…' : '导入 CSV' }}
      </button>
      <span class="muted ci-hint">
        模板必需列：sku / marketplace_id / title；可选 asin / brand / price / currency /
        category / product_type / size_tier / weight_g / status。
        按<b>列名</b>定位，你可自行增列。
      </span>
    </div>

    <p v-if="error" class="error-zone" role="alert">{{ error }}</p>

    <div v-if="report" class="ci-report" data-panel="import-report">
      <div class="ci-stats">
        <span>共 {{ report.totalRows }} 行</span>
        <span class="ok">新增 {{ report.insertedRows }}</span>
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
import { importMasterCsv, type MasterImportReport } from '@/api/listing'

/**
 * 商品主数据 CSV 导入（A6）。自足组件：自己管文件与报告状态，
 * 成功后 emit imported 让父页面刷新——不在父页面里堆导入相关的临时状态。
 *
 * 两条口径与后端一致：
 * 1. 结构错误（缺必需列/超行数）后端整批拒绝，此时报告为 null、错误进 error 条；
 * 2. 行级失败只失败那一行，报告里逐行给文件行号（含表头行），便于直接跳过去改。
 */
const emit = defineEmits<{ (e: 'imported'): void }>()

const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const uploading = ref(false)
const error = ref('')
const report = ref<MasterImportReport | null>(null)

const onFileChange = () => {
  file.value = fileInput.value?.files?.[0] ?? null
  // 换文件就清掉上一份的报告，否则新旧结果混在一起会看错行号
  report.value = null
  error.value = ''
}

const upload = async () => {
  const shopId = localStorage.getItem('current_shop_id')
  if (!file.value || !shopId) return
  uploading.value = true
  error.value = ''
  try {
    const res = await importMasterCsv(shopId, file.value)
    if (res?.code !== 200) {
      // 结构错误走这里：整批没导，报告不渲染
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
.csv-import { margin-bottom: 0.75rem; }
.ci-row { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
.ci-file { font-size: 0.8125rem; }
.ci-hint { font-size: 0.75rem; }
.ci-report { margin-top: 0.5rem; padding: 0.625rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.ci-stats { display: flex; gap: 0.875rem; font-size: 0.8125rem; flex-wrap: wrap; }
.ci-stats .ok { color: var(--color-primary); }
.ci-stats .fail { color: var(--color-error, #c62828); font-weight: 600; }
.ci-errors { margin-top: 0.5rem; }
.error-zone { background: var(--color-error-light, #fdecea); color: var(--color-error, #c62828); padding: 0.5rem 0.75rem; border-radius: var(--radius-md); font-size: 0.8125rem; margin-top: 0.5rem; }
</style>
