<script setup lang="ts">
/**
 * 数据导入（货件主单 / 运单轨迹，从 LogisticsDashboard.vue 拆出，§S33.16）。
 *
 * ## 为什么这块能整个搬进来
 * 两份 JSON 文本框 + 一个结果展示，只依赖自己那份状态
 * （shipmentJson / trackingJson / importReport）。两个 import handler
 * 也没有第二个调用者。所以表单、请求、结果一起来，页壳不必再持有一份
 * 「别人正在粘贴的 JSON」。
 *
 * ## 但导入成功后要刷新看板，那件半留在页壳
 * 导入会改写货件与轨迹，概览 / 列表 / 承运商的数字全部过期。
 * 所以成功后 emit reload-board 交回页壳执行（页壳的 reload() 还要顺带
 * 重拉在途货件列表）。**不在本组件里调各种 loader**——那些 loader 属于页shell。
 *
 * ## 解析失败要说清是哪种失败
 * 「JSON 语法错」和「语法对但不是数组」是两件事，处理方式不同
 * （前者去修文件，后者去修结构），提示必须能区分。
 * 所以 parseRows 分开两种提示，且都带后端/解析器的原文。
 */
import { ref } from 'vue'
import {
  importShipments,
  importTracking,
  type ImportReport,
  type ImportShipmentRow,
  type ImportTrackingRow
} from '../api/logistics'
import { useToast } from '../composables/useToast'
import { useShopGuard } from '../composables/useShopGuard'

const props = defineProps<{
  /** 当前是否激活 */
  active: boolean
  /** 页级操作互斥标记 */
  busy: boolean
}>()

const emit = defineEmits<{
  /** 导入成功后：货件/轨迹已变，请求页壳重拉看板与列表 */
  (e: 'reload-board'): void
}>()

const { showToast } = useToast()
const { refreshShop } = useShopGuard()

const shipmentJson = ref('')
const trackingJson = ref('')
const importReport = ref<ImportReport | null>(null)

const shipmentExample = JSON.stringify(
  [
    {
      shipmentNo: 'SHP20260818001',
      carrier: 'COSCO',
      masterTrackingNo: 'COSU1234567',
      shippingMethod: 'SEA',
      originPort: 'Shenzhen',
      destinationPort: 'LAX9',
      boxCount: 120,
      weight: 2400,
      eta: '2026-09-20'
    }
  ],
  null,
  2
)

const trackingExample = JSON.stringify(
  [
    {
      shipmentNo: 'SHP20260818001',
      trackingNo: 'COSU1234567',
      events: [
        { status: '已开船', location: '深圳港', eventTime: '2026-08-05T09:00:00+08:00' },
        { status: 'IN_TRANSIT', location: '太平洋', eventTime: '2026-08-12T09:00:00+08:00' },
        { status: '清关中', location: '洛杉矶港', eventTime: '2026-08-16T21:30:00-07:00' }
      ]
    }
  ],
  null,
  2
)

const shipmentPlaceholder = `[
  { "shipmentNo": "SHP20260818001", "carrier": "COSCO", "masterTrackingNo": "COSU1234567", "eta": "2026-09-20" }
]`
const trackingPlaceholder = `[
  { "shipmentNo": "SHP20260818001", "events": [ { "status": "已开船", "eventTime": "2026-08-05T09:00:00+08:00" } ] }
]`

/** 解析粘贴的 JSON。解析失败时给出具体原因，而不是笼统的「导入失败」 */
const parseRows = <T,>(text: string): T[] | null => {
  try {
    const parsed = JSON.parse(text)
    if (!Array.isArray(parsed)) {
      showToast('内容必须是 JSON 数组', 'error')
      return null
    }
    return parsed as T[]
  } catch (e) {
    showToast('JSON 解析失败：' + (e instanceof Error ? e.message : '格式错误'), 'error')
    return null
  }
}

const doImportShipments = async () => {
  const rows = parseRows<ImportShipmentRow>(shipmentJson.value)
  if (!rows) return
  await runImport(() => importShipments(refreshShop(), rows), '货件主单')
}

const doImportTracking = async () => {
  const rows = parseRows<ImportTrackingRow>(trackingJson.value)
  if (!rows) return
  await runImport(() => importTracking(refreshShop(), rows), '运单轨迹')
}

const runImport = async (
  fn: () => Promise<{ code: number; message: string; data: ImportReport }>,
  label: string
) => {
  if (props.busy) return
  try {
    const res = await fn()
    if (res?.code === 200 && res.data) {
      importReport.value = res.data
      const r = res.data
      showToast(
        `${label}导入完成：成功 ${r.succeededRows} / 失败 ${r.failedRows}`,
        r.failedRows > 0 ? 'error' : 'success'
      )
      // 先展示结果，再请页壳刷新看板。
      //
      // 顺序很重要：页壳的 reload() 会把 loading 置 true，而整个 tab 区在
      // `v-if="loading"` 下——为 true 时被骨架屏整块替换，本组件随之卸载，
      // importReport 是组件内状态，会一起丢。症状是「toast 说成功、
      // 结果面板却不出现」。先让用户看到结果，再触发刷新。
      // （刷新完成后面板重新挂载，结果不再显示——这是可接受的：
      //   看板数字已经更新，结果是一次性反馈，不是常驻状态。）
      //
      // 不用 nextTick：结果是同步赋值的，DOM 在同一个 microtask 里就更新完了。
      emit('reload-board')
    } else {
      showToast(res?.message || `${label}导入失败`, 'error')
    }
  } catch (e) {
    showToast(`${label}导入失败，请稍后重试`, 'error')
  }
}
</script>

<template>
  <div v-if="props.active">

            <div class="import-grid">
              <div class="chart-card">
                <h3>① 导入货件主单</h3>
                <p class="card-desc">
                  轨迹要挂在货件上，因此<strong>必须先有货件</strong>。按货件编号 upsert，
                  同一份文件可安全重复导入（已存在则只补齐本次给出的字段）。
                </p>
                <textarea
                  v-model="shipmentJson"
                  class="json-input"
                  spellcheck="false"
                  :placeholder="shipmentPlaceholder"
                  aria-label="货件主单 JSON"
                ></textarea>
                <div class="import-actions">
                  <button class="primary-btn" :disabled="busy || !shipmentJson.trim()" @click="doImportShipments">
                    导入货件主单
                  </button>
                  <button class="link-btn" @click="shipmentJson = shipmentExample">填入示例</button>
                </div>
              </div>

              <div class="chart-card">
                <h3>② 导入运单轨迹</h3>
                <p class="card-desc">
                  可提交<strong>全量历史轨迹</strong>：已存在的轨迹点会计入「跳过」而非重复插入，
                  因此不必自行维护增量。状态填承运商原文即可，服务端统一映射。
                </p>
                <textarea
                  v-model="trackingJson"
                  class="json-input"
                  spellcheck="false"
                  :placeholder="trackingPlaceholder"
                  aria-label="运单轨迹 JSON"
                ></textarea>
                <div class="import-actions">
                  <button class="primary-btn" :disabled="busy || !trackingJson.trim()" @click="doImportTracking">
                    导入运单轨迹
                  </button>
                  <button class="link-btn" @click="trackingJson = trackingExample">填入示例</button>
                </div>
              </div>
            </div>

            <div v-if="importReport" class="chart-card report-card">
              <div class="card-head">
                <h3>最近一次导入结果</h3>
                <button class="link-btn" @click="importReport = null">清除</button>
              </div>
              <div class="report-grid">
                <div class="report-item"><b>{{ importReport.totalRows }}</b><span>总行数</span></div>
                <div class="report-item"><b>{{ importReport.succeededRows }}</b><span>成功</span></div>
                <div class="report-item" :class="{ 'cell-alert': importReport.failedRows > 0 }">
                  <b>{{ importReport.failedRows }}</b><span>失败</span>
                </div>
                <div class="report-item"><b>{{ importReport.createdShipments }}</b><span>新建货件</span></div>
                <div class="report-item"><b>{{ importReport.updatedShipments }}</b><span>补齐货件</span></div>
                <div class="report-item"><b>{{ importReport.acceptedEvents }}</b><span>写入轨迹点</span></div>
                <div class="report-item"><b>{{ importReport.skippedEvents }}</b><span>跳过（已存在）</span></div>
                <div class="report-item"><b>{{ importReport.statusChangedShipments }}</b><span>状态变更</span></div>
              </div>

              <div v-if="importReport.unmatched.length" class="report-block">
                <div class="report-title">以下运单在系统内没有对应货件，请先导入货件主单：</div>
                <div class="chip-wrap">
                  <span v-for="u in importReport.unmatched" :key="u" class="chip">{{ u }}</span>
                </div>
              </div>
              <div v-if="importReport.errors.length" class="report-block">
                <div class="report-title">行级错误：</div>
                <ul class="err-list">
                  <li v-for="(e, i) in importReport.errors" :key="i">{{ e }}</li>
                </ul>
                <div v-if="importReport.errorsTruncated" class="report-title">
                  错误过多，仅展示前若干条
                </div>
              </div>
              <p
                v-if="!importReport.unmatched.length && !importReport.errors.length && importReport.failedRows === 0"
                class="report-ok"
              >
                全部处理成功。若「写入轨迹点」为 0 而「跳过」大于 0，说明这批数据此前已导入过（幂等生效）。
              </p>
            </div>
          
  </div>
</template>

<style scoped>
/* 不定义任何样式：import-grid / chart-card / card-desc / json-input /
 * import-actions / primary-btn / report-* / chip-wrap / chip / err-list /
 * cell-alert / link-btn 全部来自 src/style.css 与共享样式表。
 * 由 tools/release/test_css_scope_parity.py 盯住。
 */
</style>
