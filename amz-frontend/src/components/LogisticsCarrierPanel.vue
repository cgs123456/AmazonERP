<script setup lang="ts">
/**
 * 承运商表现表（从 LogisticsDashboard.vue 拆出，§S33.15）。
 *
 * ## 这块最好拆：纯只读
 * 只渲染 `carriers` 一个 prop，没有任何 handler、没有任何本地状态。
 * 它是「看一眼哪个承运商靠不住」的那张表，不是操作面板。
 *
 * ## 延误与异常要标红，但只在非零时
 * `c.delayed > 0` 才加 `cell-alert`。0 不标——整列都灰等于没标，
 * 而这里要回答的是「哪几家有问题」。
 *
 * ## 平均时效的占位不是 0
 * 样本不足时后端给 null，显示「暂无样本」。显示 0 天会让运营以为这家承运商
 * 快得不合常理，从而在选商时优先选它。
 */
import type { CarrierPerformance } from '../api/logistics'
import { fmtRate } from './logisticsFormat'

const props = defineProps<{
  /** 当前是否激活（页壳的 tab 是不是本面板） */
  active: boolean
  /** 承运商表现数据，由页壳的 loadCarriers 拉取 */
  carriers: CarrierPerformance[]
}>()
</script>

<template>
  <div v-if="props.active">

            <div class="table-card">
              <table class="data-table">
                <thead>
                  <tr>
                    <th>承运商</th>
                    <th>总货件</th>
                    <th>在跟踪</th>
                    <th>已送达</th>
                    <th>延误</th>
                    <th>异常</th>
                    <th>平均时效</th>
                    <th>延误率</th>
                    <th>异常率</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="!carriers.length">
                    <td colspan="9" class="empty-row">暂无数据</td>
                  </tr>
                  <tr v-for="c in carriers" :key="c.carrier">
                    <td>{{ c.carrier }}</td>
                    <td>{{ c.total }}</td>
                    <td>{{ c.active }}</td>
                    <td>{{ c.delivered }}</td>
                    <td :class="{ 'cell-alert': c.delayed > 0 }">{{ c.delayed }}</td>
                    <td :class="{ 'cell-alert': c.exception > 0 }">{{ c.exception }}</td>
                    <td>{{ c.avgTransitDays === null ? '暂无样本' : c.avgTransitDays + ' 天' }}</td>
                    <td>{{ fmtRate(c.delayedRate) }}</td>
                    <td>{{ fmtRate(c.exceptionRate) }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="foot-note">
              平均时效以「承运商确认送达」的轨迹事件时间为终点计算，样本不足时显示「暂无样本」而非 0，
              避免把小样本误读为高效。
            </p>
          
  </div>
</template>

<style scoped>
/* 不定义任何样式：table-card / data-table / cell-alert / empty-row /
 * foot-note 全部来自 src/style.css 与共享样式表。
 * 由 tools/release/test_css_scope_parity.py 盯住。
 */
</style>
