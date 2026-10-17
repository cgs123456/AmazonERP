<script setup lang="ts">
/**
 * 运费比价面板（从 LogisticsDashboard.vue 拆出，§S33.9）。
 *
 * ## 为什么这块能整个搬进来
 * 比价是报价 tab 里**唯一全自助的一段**：填表单 → 调 compareQuotes → 展示结果，
 * 不依赖看板数据、不写任何单据、失败只需 toast。所以表单状态（cmpForm / cmpResult /
 * cmpLoading）与请求逻辑一起搬进来，页壳不必再为一份表单持有三个 ref。
 *
 * ## 但错误区仍在页壳
 * loader 失败要进页级错误区（blockErrors），那属于页壳。本组件的请求失败
 * **不影响报价看板**（看板已加载完成），所以用 toast 而不占页级错误区——
 * 与页壳原行为一致（原来也是 showToast）。
 *
 * ## 一条不能省的校验
 * 重量与体积至少填一个。后端在此情况下必然拒绝，且两个都空时 `weightKg || undefined`
 * 会双双变 undefined——按钮禁用条件与提交参数必须同一口径，否则出现
 * 「按钮能点但请求一定失败」。
 */
import { ref } from 'vue'
import { compareQuotes, type QuoteCompareResult } from '../api/logistics'
import { useShopGuard } from '../composables/useShopGuard'
import { useToast } from '../composables/useToast'
import { methodLabel, basisLabel } from './logisticsQuoteLabels'

const { refreshShop } = useShopGuard()
const { showToast } = useToast()

const fmtAmount = (v?: number | null) => (v === null || v === undefined ? '—' : v.toFixed(2))

/* ---------- 比价表单状态（本组件自己的状态） ---------- */

/** 比价入参。重量与体积至少填一个，两者都填时服务端按计费价取高 */
const cmpForm = ref<{ originPort: string; destinationPort: string; weightKg?: number; volumeCbm?: number }>({
  originPort: '',
  destinationPort: ''
})
const cmpResult = ref<QuoteCompareResult | null>(null)
const cmpLoading = ref(false)

const doCompare = async () => {
  const shopId = refreshShop()
  if (!shopId) return
  cmpLoading.value = true
  try {
    const res = await compareQuotes(shopId, {
      originPort: cmpForm.value.originPort.trim(),
      destinationPort: cmpForm.value.destinationPort.trim(),
      weightKg: cmpForm.value.weightKg || undefined,
      volumeCbm: cmpForm.value.volumeCbm || undefined
    })
    if (res?.code === 200 && res.data) {
      cmpResult.value = res.data
    } else {
      // 后端会说明具体缺什么（例如「请至少提供重量或体积」），直接透出比笼统提示有用
      showToast(res?.message || '比价失败', 'error')
    }
  } catch (e) {
    showToast('比价失败，请稍后重试', 'error')
  } finally {
    cmpLoading.value = false
  }
}
</script>

<template>
            <div class="chart-card mt">
              <h3>运费比价</h3>
              <p class="card-desc">
                填写货量即可比较该航线上所有有效报价。
                计费口径为<strong>重量价与体积价取高</strong>，再叠加最低收费与燃油附加费——
                这也是承运商的实际计费规则，只按重量算会低估运费。
                不同币种的金额不可直接比较，因此按币种分别给出推荐。
              </p>
              <div class="cmp-form">
                <label>
                  起运港
                  <input v-model="cmpForm.originPort" placeholder="如 Shenzhen" />
                </label>
                <label>
                  目的港
                  <input v-model="cmpForm.destinationPort" placeholder="如 Los Angeles" />
                </label>
                <label>
                  重量 (kg)
                  <input v-model.number="cmpForm.weightKg" type="number" min="0" placeholder="可留空" />
                </label>
                <label>
                  体积 (m³)
                  <input v-model.number="cmpForm.volumeCbm" type="number" min="0" step="0.01" placeholder="可留空" />
                </label>
                <button
                  class="primary-btn"
                  :disabled="cmpLoading || !cmpForm.originPort || !cmpForm.destinationPort
                    || (!cmpForm.weightKg && !cmpForm.volumeCbm)"
                  @click="doCompare"
                >
                  计算比价
                </button>
              </div>

              <div v-if="cmpResult" class="cmp-result">
                <div v-if="cmpResult.warnings.length" class="report-block">
                  <ul class="err-list note">
                    <li v-for="(w, i) in cmpResult.warnings" :key="i">{{ w }}</li>
                  </ul>
                </div>
                <div v-if="cmpResult.scanTruncated" class="cmp-best multi">
                  报价扫描达到上限：仅使用前 {{ cmpResult.scanLimit }} 条报价，本次结果不完整，
                  已禁用最低价推荐。请缩小航线范围后重试。
                </div>
                <div v-else-if="cmpResult.recommended" class="cmp-best">
                  推荐承运商：<b>{{ cmpResult.recommended }}</b>
                </div>
                <div v-else class="cmp-best multi">
                  本航线存在多种币种，无法给出唯一的「最便宜」结论，请按下方币种分组分别判断。
                </div>
                <table class="data-table">
                  <thead>
                    <tr>
                      <th>承运商</th>
                      <th>方式</th>
                      <th>时效</th>
                      <th>计费口径</th>
                      <th>运费</th>
                      <th>燃油附加</th>
                      <th>合计</th>
                      <th>币种</th>
                      <th>失效日期</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-if="!cmpResult.quotes.length">
                      <td colspan="9" class="empty-row">该航线没有有效报价</td>
                    </tr>
                    <tr v-for="q in cmpResult.quotes" :key="q.quoteId">
                      <td>
                        {{ q.carrierName }}
                        <span
                          v-if="cmpResult.recommendedByCurrency[q.currency] === q.carrierName"
                          class="status-tag tone-success"
                        >该币种最低</span>
                      </td>
                      <td>{{ methodLabel(q.serviceType) }}</td>
                      <td>{{ q.transitDays != null ? q.transitDays + ' 天' : '—' }}</td>
                      <td>
                        {{ basisLabel(q.chargeableBasis) }}
                        <span v-if="q.minChargeApplied" class="status-tag tone-muted">已套最低收费</span>
                      </td>
                      <td class="mono">{{ fmtAmount(q.freightCost) }}</td>
                      <td class="mono">{{ fmtAmount(q.fuelSurcharge) }}</td>
                      <td class="mono"><b>{{ fmtAmount(q.totalCost) }}</b></td>
                      <td>{{ q.currency }}</td>
                      <td class="mono">{{ q.expiryDate || '不限' }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </div>

</template>

<style scoped>
/* 不定义任何样式：chart-card / card-desc / cmp-form / cmp-result / cmp-best /
 * report-block / err-list / data-table / status-tag / mono / empty-row / primary-btn
 * 全部来自共享样式表与 src/style.css。
 * 由 tools/release/test_css_scope_parity.py 盯住。
 */
</style>
