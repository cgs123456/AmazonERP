<template>
  <div class="csv-import" data-panel="supplier-price-csv-import">
    <div class="ci-row">
      <label class="ci-supplier">
        供应商
        <select v-model="supplierId" :disabled="loading || uploading" @change="emit('change')">
          <option value="">{{ loading ? '加载中…' : (suppliers.length ? '请选择供应商' : '该店铺暂无供应商') }}</option>
          <option v-for="s in suppliers" :key="s.id" :value="s.id">
            {{ s.supplierName }}（ID {{ s.id }}）
          </option>
        </select>
      </label>
      <input ref="fileInput" type="file" accept=".csv" class="ci-file" @change="onFileChange" />
      <button class="page-btn" :disabled="!file || !supplierId || uploading" @click="upload">
        {{ uploading ? '导入中…' : '导入价目表' }}
      </button>
    </div>
    <p class="muted ci-hint">
      选定供应商后上传它的价目表，每行只需填 sku 定位（不用手抄内部 supplier_id）。
      可维护列：supply_price / moq / lead_time_days / supplier_sku_code / supplier_offer_id /
      packaging_spec / unit_weight / unit_volume，留空则保持原值。<br />
      <b>只更新已存在的供货关系，不新建；is_preferred（设首选）与身份/状态列不允许批量改</b>——
      设首选要先清同 SKU 其他首选（跨行副作用），请走单条表单。
    </p>

    <p v-if="error" class="error-zone" role="alert">{{ error }}</p>

    <div v-if="report" class="ci-report" data-panel="supplier-price-import-report">
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
import { ref, onMounted } from 'vue'
import { useShopGuard } from '@/composables/useShopGuard'
import { listSuppliers, importSupplierPriceCsv, type Supplier, type SupplierPriceImportReport } from '@/api/procurement'

/**
 * 供货价 CSV 批量维护面板（A6 第三个域）。自足：自己拉供应商列表、管文件与报告状态，
 * 成功后 emit imported 让父页刷新供货关系列表；供应商变化 emit change 让父页可联动。
 *
 * 三个导入域（商品 / 库存元数据 / 供货价）都是独立小面板，不合并成一个共享组件：
 * 后端行循环已统一抽到 CsvImportSupport（重复已发生才抽），但前端各面板的
 * 可维护列、拒绝列与「新增」计数语义不同——合并反而要在模板里堆条件分支。
 * 本域特有：供应商下拉（把内部 supplier_id 收进界面，不让使用者抄进 Excel）。
 */
const emit = defineEmits<{ (e: 'imported'): void; (e: 'change'): void }>()
const { currentShopId, refreshShop } = useShopGuard()

const fileInput = ref<HTMLInputElement | null>(null)
const file = ref<File | null>(null)
const suppliers = ref<Supplier[]>([])
const supplierId = ref<string>('')
const loading = ref(false)
const uploading = ref(false)
const error = ref('')
const report = ref<SupplierPriceImportReport | null>(null)

const loadSuppliers = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  loading.value = true
  try {
    const res = await listSuppliers(shopId)
    suppliers.value = res?.code === 200 && Array.isArray(res.data) ? res.data : []
  } catch {
    suppliers.value = [] // 拉不到供应商下拉不阻断，但下方提示会显示「暂无」
  } finally {
    loading.value = false
  }
}

const onFileChange = () => {
  file.value = fileInput.value?.files?.[0] ?? null
  // 换文件清掉上一份报告，否则新旧结果混在一起会看错行号
  report.value = null
  error.value = ''
}

const upload = async () => {
  const shopId = currentShopId.value || refreshShop()
  if (!file.value || !shopId || !supplierId.value) return
  uploading.value = true
  error.value = ''
  try {
    const res = await importSupplierPriceCsv(shopId, Number(supplierId.value), file.value)
    if (res?.code !== 200) {
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

onMounted(loadSuppliers)
</script>

<style scoped>
.csv-import { margin: 0 0 0.75rem; }
.ci-row { display: flex; gap: 0.5rem; align-items: center; flex-wrap: wrap; }
.ci-supplier { display: inline-flex; align-items: center; gap: 0.375rem; font-size: 0.8125rem; }
.ci-supplier select { padding: 0.375rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.8125rem; }
.ci-file { font-size: 0.8125rem; }
.ci-hint { font-size: 0.75rem; margin: 0.375rem 0 0; line-height: 1.6; }
.ci-report { margin-top: 0.5rem; padding: 0.625rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); }
.ci-stats { display: flex; gap: 0.875rem; font-size: 0.8125rem; flex-wrap: wrap; }
.ci-stats .ok { color: var(--color-primary); }
.ci-stats .fail { color: var(--color-error, #c62828); font-weight: 600; }
.ci-errors { margin-top: 0.5rem; }
.error-zone { background: var(--color-error-light, #fdecea); color: var(--color-error, #c62828); padding: 0.5rem 0.75rem; border-radius: var(--radius-md); font-size: 0.8125rem; margin-top: 0.5rem; }
</style>
