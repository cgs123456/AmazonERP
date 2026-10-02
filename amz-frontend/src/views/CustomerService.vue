<template>
  <div class="customer-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">客服中心</h1>
        <p class="hero-subtitle">工单 · 邮件模板与任务 · 差评跟进 · RMA · 索评</p>
      </div>

      <div v-if="!currentShopId" class="shop-tip">请先在右上角选择店铺后再查看客服数据。</div>

      <div v-if="errors.length" class="error-zone" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="16" />
        <span>{{ errors.join('；') }}</span>
      </div>

      <div class="notice-zone" role="note">
        三条通道现状：① 邮件发送通道未接入（后端没有 SMTP/Messaging 客户端）——「处理待发队列」在非模拟环境只会把任务标成
        FAILED 并写明原因，不会真的发信；② 索评与「差评匹配订单」依赖 SP-API，模拟环境才会造
        SIMULATED-* 记录；③ 工单分类是关键词规则匹配，不是模型判定。
      </div>

      <template v-if="currentShopId">
        <div class="tabs">
          <button v-for="t in TABS" :key="t.key" class="tab" :class="{ active: tab === t.key }"
                  @click="gotoTab(t.key)">{{ t.label }}</button>
        </div>

        <!-- ==================== 工单 ==================== -->
        <div v-if="tab === 'ticket'" class="tab-panel" data-panel="ticket">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">状态
                <select v-model="ticketStatus" @change="loadTickets()">
                  <option value="">全部</option>
                  <option v-for="s in TICKET_STATUS" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <label class="filter">分类<input v-model="ticketCategory" @keyup.enter="loadTickets()" /></label>
              <button class="action-btn" :disabled="tickets.loading.value" @click="loadTickets()">刷新</button>
              <button class="action-btn" @click="openModal('ticket')">新建工单</button>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>订单号</th><th>买家</th><th>渠道</th><th>分类</th><th>情感</th><th>优先级</th><th>状态</th><th>内容</th><th>回复</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="t in tickets.rows.value" :key="t.id">
                  <td class="mono">{{ t.amazonOrderId || '-' }}</td>
                  <td>{{ t.buyerName || t.buyerId || '-' }}</td>
                  <td>{{ t.channel || '-' }}</td>
                  <td>{{ t.category || '-' }}</td>
                  <td><span class="status-tag" :class="sentimentClass(t.sentiment)">{{ t.sentiment || '-' }}</span></td>
                  <td>{{ t.priority || '-' }}</td>
                  <td><span class="status-tag" :class="ticketClass(t.status)">{{ t.status }}</span></td>
                  <td class="cell-clip" :title="t.content">{{ t.content || '-' }}</td>
                  <td class="cell-clip" :title="t.reply">{{ t.reply || '-' }}</td>
                  <td>
                    <button class="action-btn" :disabled="busy || t.status === 'RESOLVED'" @click="openModal('reply', { ticket: t })">回复</button>
                  </td>
                </tr>
                <tr v-if="!tickets.loading.value && !tickets.rows.value.length">
                  <td colspan="10" class="empty-row">该店铺暂无工单</td>
                </tr>
              </tbody>
            </table>
            <div class="table-pager">
              <span class="page-info">{{ pagerText(tickets) }}</span>
              <div class="page-actions">
                <button v-if="tickets.truncated.value" class="page-btn" :disabled="tickets.loading.value"
                        @click="loadTickets(true)">加载下一页</button>
              </div>
            </div>
          </div>
        </div>

        <!-- ==================== 邮件模板 ==================== -->
        <div v-if="tab === 'template'" class="tab-panel" data-panel="template">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">类型
                <select v-model="tplType" @change="loadTemplates()">
                  <option value="">全部</option>
                  <option v-for="t in TEMPLATE_TYPES" :key="t" :value="t">{{ t }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="templates.loading.value" @click="loadTemplates()">刷新</button>
              <button class="action-btn" @click="openModal('template')">新建模板</button>
              <span class="muted">「按事件触发邮件」的下拉项来自这里启用中的 triggerEvent</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>模板名</th><th>类型</th><th>语言</th><th>触发事件</th><th>延迟(小时)</th><th>主题</th><th>正文</th><th>状态</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="tp in templates.rows.value" :key="tp.id">
                  <td>{{ tp.templateName }}</td>
                  <td>{{ tp.templateType || '-' }}</td>
                  <td>{{ tp.language || '-' }}</td>
                  <td class="mono">{{ tp.triggerEvent || '-' }}</td>
                  <td>{{ tp.triggerDelayHours ?? 0 }}</td>
                  <td class="cell-clip">{{ tp.subject || '-' }}</td>
                  <td class="cell-clip" :title="tp.body">{{ tp.body }}</td>
                  <td><span class="status-tag" :class="truthy(tp.enabled) ? 'healthy' : 'unknown'">{{ truthy(tp.enabled) ? '启用' : '停用' }}</span></td>
                  <td>
                    <button class="action-btn" :disabled="busy" @click="toggleTpl(tp, !truthy(tp.enabled))">{{ truthy(tp.enabled) ? '停用' : '启用' }}</button>
                    <button class="action-btn" @click="openModal('template', { template: tp })">编辑</button>
                  </td>
                </tr>
                <tr v-if="!templates.loading.value && !templates.rows.value.length">
                  <td colspan="9" class="empty-row">该店铺还没有邮件模板，自动触发不会产生任务</td>
                </tr>
              </tbody>
            </table>
            <div class="table-pager">
              <span class="page-info">{{ pagerText(templates) }}</span>
              <div class="page-actions">
                <button v-if="templates.truncated.value" class="page-btn" :disabled="templates.loading.value"
                        @click="loadTemplates(true)">加载下一页</button>
              </div>
            </div>
          </div>
        </div>

        <!-- ==================== 邮件任务 ==================== -->
        <div v-if="tab === 'task'" class="tab-panel" data-panel="task">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">状态
                <select v-model="taskStatus" @change="loadTasks()">
                  <option value="">全部</option>
                  <option v-for="s in TASK_STATUS" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="tasks.loading.value" @click="loadTasks()">刷新</button>
              <button class="action-btn" @click="openModal('manualTask')">手动建任务</button>
              <button class="action-btn" @click="openModal('trigger')">按事件触发</button>
              <button class="action-btn danger" :disabled="busy" @click="confirm(processConfirm)">处理待发队列</button>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>订单号</th><th>ASIN</th><th>收件人</th><th>主题</th><th>状态</th><th>计划时间</th><th>发送时间</th><th>来源</th><th>失败原因</th></tr>
              </thead>
              <tbody>
                <tr v-for="tk in tasks.rows.value" :key="tk.id">
                  <td class="mono">{{ tk.amazonOrderId || '-' }}</td>
                  <td class="mono">{{ tk.asin || '-' }}</td>
                  <td>{{ tk.buyerEmail || '（缺收件人）' }}</td>
                  <td class="cell-clip">{{ tk.subject || '-' }}</td>
                  <td><span class="status-tag" :class="taskClass(tk.status)">{{ tk.status }}</span></td>
                  <td class="mono">{{ tk.scheduledTime || '-' }}</td>
                  <td class="mono">{{ tk.sentTime || '-' }}</td>
                  <td>{{ tk.source || '-' }}</td>
                  <td class="reason">{{ tk.failureReason || '-' }}</td>
                </tr>
                <tr v-if="!tasks.loading.value && !tasks.rows.value.length">
                  <td colspan="9" class="empty-row">暂无邮件任务</td>
                </tr>
              </tbody>
            </table>
            <div class="table-pager">
              <span class="page-info">{{ pagerText(tasks) }}</span>
              <div class="page-actions">
                <button v-if="tasks.truncated.value" class="page-btn" :disabled="tasks.loading.value"
                        @click="loadTasks(true)">加载下一页</button>
              </div>
            </div>
            <p v-if="processReport" class="muted">处理结果：{{ JSON.stringify(processReport) }}</p>
          </div>
        </div>

        <!-- ==================== 差评跟进与索评 ==================== -->
        <div v-if="tab === 'review'" class="tab-panel" data-panel="review">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">状态
                <select v-model="reviewStatus" @change="loadReviews()">
                  <option value="">全部</option>
                  <option v-for="s in REVIEW_STATUS" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <label class="filter">最高评分<input class="cell-input" type="number" min="1" max="5" v-model="reviewMaxRating" /></label>
              <button class="action-btn" :disabled="reviews.loading.value" @click="loadReviews()">刷新</button>
              <button class="action-btn" @click="openModal('review')">登记差评</button>
              <button class="action-btn danger" :disabled="busy" @click="confirm(solicitConfirm)">批量索评</button>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>ASIN</th><th>评分</th><th>标题</th><th>日期</th><th>购买认证</th><th>状态</th><th>匹配订单</th><th>跟进任务</th><th>操作</th></tr>
              </thead>
              <tbody>
                <tr v-for="r in reviews.rows.value" :key="r.id">
                  <td class="mono">{{ r.asin }}</td>
                  <td :class="r.reviewRating <= 2 ? 'neg' : ''">{{ r.reviewRating }}</td>
                  <td class="cell-clip" :title="r.reviewTitle">{{ r.reviewTitle || '-' }}</td>
                  <td class="mono">{{ r.reviewDate || '-' }}</td>
                  <td>{{ truthy(r.verifiedPurchase) ? '已认证' : '-' }}</td>
                  <td><span class="status-tag" :class="reviewClass(r.status)">{{ r.status }}</span></td>
                  <td class="mono">{{ r.matchedOrderId || '-' }}</td>
                  <td class="mono">{{ r.contactEmailTaskId || '-' }}</td>
                  <td>
                    <button class="action-btn" :disabled="busy || !!r.matchedOrderId" @click="confirm(matchConfirm(r))">匹配订单</button>
                    <button class="action-btn" :disabled="busy" @click="followUp(r)">生成跟进邮件</button>
                  </td>
                </tr>
                <tr v-if="!reviews.loading.value && !reviews.rows.value.length">
                  <td colspan="9" class="empty-row">暂无差评记录</td>
                </tr>
              </tbody>
            </table>
            <div class="table-pager">
              <span class="page-info">{{ pagerText(reviews) }}</span>
              <div class="page-actions">
                <button v-if="reviews.truncated.value" class="page-btn" :disabled="reviews.loading.value"
                        @click="loadReviews(true)">加载下一页</button>
              </div>
            </div>
          </div>

          <div class="table-card">
            <div class="block-title">索评记录（列表真实，「批量索评」依赖 SP-API）</div>
            <table class="data-table">
              <thead><tr><th>订单号</th><th>ASIN</th><th>渠道</th><th>状态</th><th>原因</th></tr></thead>
              <tbody>
                <tr v-for="so in solicitations" :key="so.id">
                  <td class="mono">{{ so.amazonOrderId }}</td>
                  <td class="mono">{{ so.asin }}</td>
                  <td>{{ so.channel }}</td>
                  <td><span class="status-tag" :class="so.status === 'SENT' ? 'healthy' : 'risk'">{{ so.status }}</span></td>
                  <td class="reason">{{ so.failureReason || '-' }}</td>
                </tr>
                <tr v-if="!solicitations.length"><td colspan="5" class="empty-row">还没有索评记录</td></tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- ==================== RMA ==================== -->
        <div v-if="tab === 'rma'" class="tab-panel" data-panel="rma">
          <div class="table-card">
            <div class="filter-row">
              <label class="filter">状态
                <select v-model="rmaStatus" @change="loadRmas()">
                  <option value="">全部</option>
                  <option v-for="s in RMA_STATUS" :key="s" :value="s">{{ s }}</option>
                </select>
              </label>
              <button class="action-btn" :disabled="rmas.loading.value" @click="loadRmas()">刷新</button>
              <button class="action-btn" @click="openModal('rma')">新建 RMA</button>
              <span class="muted">订单号后端不校验是否存在或属于本店铺，请自行核对</span>
            </div>
            <table class="data-table">
              <thead>
                <tr><th>RMA 号</th><th>订单号</th><th>ASIN/SKU</th><th>类型</th><th>商品状态</th><th>退款</th><th>状态</th><th>面单</th><th>运单</th><th>改状态</th></tr>
              </thead>
              <tbody>
                <tr v-for="r in rmas.rows.value" :key="r.id">
                  <td class="mono">{{ r.rmaNo || r.id }}</td>
                  <td class="mono">{{ r.amazonOrderId }}</td>
                  <td class="mono">{{ r.asin || '-' }} / {{ r.sku || '-' }}</td>
                  <td>{{ r.returnType || '-' }}</td>
                  <td>{{ r.productCondition || '-' }}</td>
                  <td>{{ r.refundAmount ?? '-' }}</td>
                  <td><span class="status-tag" :class="rmaClass(r.status)">{{ r.status }}</span></td>
                  <td class="cell-clip"><a v-if="r.labelUrl" :href="r.labelUrl" target="_blank" rel="noopener">面单</a><span v-else>-</span></td>
                  <td class="mono">{{ r.trackingNo || '-' }}</td>
                  <td>
                    <select class="cell-input" :value="r.status" @change="changeRmaStatus(r, $event)">
                      <option v-for="s in RMA_STATUS" :key="s" :value="s">{{ s }}</option>
                    </select>
                  </td>
                </tr>
                <tr v-if="!rmas.loading.value && !rmas.rows.value.length">
                  <td colspan="10" class="empty-row">暂无 RMA 单</td>
                </tr>
              </tbody>
            </table>
            <div class="table-pager">
              <span class="page-info">{{ pagerText(rmas) }}</span>
              <div class="page-actions">
                <button v-if="rmas.truncated.value" class="page-btn" :disabled="rmas.loading.value"
                        @click="loadRmas(true)">加载下一页</button>
              </div>
            </div>
          </div>
        </div>
      </template>
    </main>

    <!-- 弹窗 -->
    <div v-if="modal" class="modal-mask" @click.self="modal = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ MODAL_TITLE[modal] }}</h3>
        <p class="modal-note">{{ MODAL_NOTE[modal] }}</p>

        <div v-if="modal === 'ticket'" class="form-grid">
          <label>订单号 *<input v-model="form.amazonOrderId" /></label>
          <label>渠道
            <select v-model="form.channel"><option v-for="c in TICKET_CHANNELS" :key="c" :value="c">{{ c }}</option></select>
          </label>
          <label>买家名<input v-model="form.buyerName" /></label>
          <label>优先级
            <select v-model="form.priority"><option v-for="p in PRIORITIES" :key="p" :value="p">{{ p }}</option></select>
          </label>
          <label class="span2">内容 *（分类与情感由后端关键词规则判定）<textarea v-model="form.content" rows="3" /></label>
        </div>

        <div v-else-if="modal === 'reply'" class="form-grid">
          <label>工单<input :value="form.ticket?.id" disabled /></label>
          <label>当前状态<input :value="form.ticket?.status" disabled /></label>
          <label class="span2">回复内容 *<textarea v-model="form.reply" rows="3" /></label>
        </div>

        <div v-else-if="modal === 'template'" class="form-grid">
          <label>模板名 *<input v-model="form.templateName" /></label>
          <label>类型
            <select v-model="form.templateType"><option v-for="t in TEMPLATE_TYPES" :key="t" :value="t">{{ t }}</option></select>
          </label>
          <label>语言<input v-model="form.language" placeholder="en" /></label>
          <label>触发事件<input v-model="form.triggerEvent" placeholder="如 NEGATIVE_REVIEW" /></label>
          <label>延迟小时<input type="number" min="0" v-model="form.triggerDelayHours" /></label>
          <label>启用
            <select v-model="form.enabled"><option :value="1">启用</option><option :value="0">停用</option></select>
          </label>
          <label class="span2">主题<input v-model="form.subject" /></label>
          <label class="span2">正文 *<textarea v-model="form.body" rows="4" /></label>
        </div>

        <div v-else-if="modal === 'manualTask'" class="form-grid">
          <label>订单号 *<input v-model="form.amazonOrderId" /></label>
          <label>模板（可选）
            <select v-model="form.templateId">
              <option :value="null">不用模板，直接填正文</option>
              <option v-for="tp in templates.rows.value" :key="tp.id" :value="tp.id">{{ tp.templateName }}</option>
            </select>
          </label>
          <label>ASIN<input v-model="form.asin" /></label>
          <label>收件人邮箱 *<input v-model="form.buyerEmail" /></label>
          <label>买家名<input v-model="form.buyerName" /></label>
          <label>主题<input v-model="form.subject" /></label>
          <label class="span2">正文 *<textarea v-model="form.body" rows="3" /></label>
        </div>

        <div v-else-if="modal === 'trigger'" class="form-grid">
          <label>事件 *
            <select v-model="form.eventType">
              <option value="">选择启用中模板的 triggerEvent</option>
              <option v-for="e in eventOptions" :key="e" :value="e">{{ e }}</option>
            </select>
          </label>
          <label>订单号 *<input v-model="form.orderId" /></label>
          <label>ASIN<input v-model="form.asin" /></label>
          <label>收件人邮箱<input v-model="form.buyerEmail" /></label>
          <label>买家名<input v-model="form.buyerName" /></label>
          <label>运单号<input v-model="form.trackingNo" /></label>
        </div>

        <div v-else-if="modal === 'review'" class="form-grid">
          <label>ASIN *<input v-model="form.asin" /></label>
          <label>评分 *<input type="number" min="1" max="5" v-model="form.reviewRating" /></label>
          <label>评论者<input v-model="form.reviewerName" /></label>
          <label>评论日期<input v-model="form.reviewDate" placeholder="2026-09-28" /></label>
          <label>亚马逊 reviewId<input v-model="form.reviewId" /></label>
          <label>已认证购买
            <select v-model="form.verifiedPurchase"><option :value="1">是</option><option :value="0">否</option></select>
          </label>
          <label class="span2">标题<input v-model="form.reviewTitle" /></label>
          <label class="span2">正文<textarea v-model="form.reviewContent" rows="3" /></label>
        </div>

        <div v-else-if="modal === 'rma'" class="form-grid">
          <label>订单号 *<input v-model="form.amazonOrderId" /></label>
          <label>处理方式
            <select v-model="form.returnType"><option v-for="t in RMA_TYPES" :key="t" :value="t">{{ t }}</option></select>
          </label>
          <label>ASIN<input v-model="form.asin" /></label>
          <label>SKU<input v-model="form.sku" /></label>
          <label>商品状态
            <select v-model="form.productCondition"><option v-for="c in RMA_CONDITIONS" :key="c" :value="c">{{ c }}</option></select>
          </label>
          <label>退款金额<input type="number" step="0.01" min="0" v-model="form.refundAmount" /></label>
          <label class="span2">退货原因 *<input v-model="form.returnReason" /></label>
          <label class="span2">备注<textarea v-model="form.remark" rows="2" /></label>
        </div>

        <div class="modal-actions">
          <button class="page-btn" @click="modal = null">取消</button>
          <button class="page-btn" :disabled="busy" @click="submitModal">提交</button>
        </div>
      </div>
    </div>

    <div v-if="confirmBox" class="modal-mask" @click.self="confirmBox = null">
      <div class="modal" role="dialog" aria-modal="true">
        <h3>{{ confirmBox.title }}</h3>
        <p class="confirm-detail">{{ confirmBox.detail }}</p>
        <div class="modal-actions">
          <button class="page-btn" @click="confirmBox = null">取消</button>
          <button class="page-btn" :disabled="busy" @click="runConfirm">确认执行</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, type Ref } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useShopGuard } from '@/composables/useShopGuard'
import type { ApiResponse } from '@/api/types'
import * as cust from '@/api/customer'
import {
  TICKET_STATUS, TICKET_CHANNELS, TASK_STATUS, REVIEW_STATUS, RMA_STATUS, RMA_TYPES, RMA_CONDITIONS
} from '@/api/customer'
import type { CustomerTicket, EmailTemplate, EmailTask, NegativeReview, Rma, ReviewSolicitation } from '@/api/customer'

type TabKey = 'ticket' | 'template' | 'task' | 'review' | 'rma'
const TABS: Array<{ key: TabKey; label: string }> = [
  { key: 'ticket', label: '工单' },
  { key: 'template', label: '邮件模板' },
  { key: 'task', label: '邮件任务' },
  { key: 'review', label: '差评与索评' },
  { key: 'rma', label: 'RMA' }
]

const PRIORITIES = ['URGENT', 'HIGH', 'NORMAL', 'LOW']
const TEMPLATE_TYPES = ['REVIEW_REQUEST', 'AFTERSALE', 'SHIPPING', 'RETURN', 'PROMOTION']
type ModalKey = 'ticket' | 'reply' | 'template' | 'manualTask' | 'trigger' | 'review' | 'rma'
const MODAL_TITLE: Record<ModalKey, string> = {
  ticket: '新建工单', reply: '回复工单', template: '邮件模板', manualTask: '手动创建邮件任务',
  trigger: '按事件触发邮件', review: '登记差评', rma: '新建 RMA'
}
const MODAL_NOTE: Record<ModalKey, string> = {
  ticket: '分类、情感、优先级由后端关键词规则判定，不是模型；订单号是自由文本，后端不校验归属。',
  reply: '回复会把工单状态推进到 REPLIED。',
  template: '模板本身不发消息：只有 triggerEvent 命中「按事件触发」才会生成 PENDING 任务。',
  manualTask: '手动任务同样不会真的发信——发送通道未接入，处理待发队列时会写明原因。',
  trigger: '事件列表来自启用中的模板；没有匹配模板时后端不会创建任务。',
  review: '登记后状态为 DETECTED；匹配订单在模拟环境才会造 SIMULATED-* 订单号。',
  rma: 'RMA 与订单没有外键关系，填错订单号不会被拦。'
}

const { currentShopId, refreshShop } = useShopGuard()
const tab = ref<TabKey>('ticket')
const errors = ref<string[]>([])
const busy = ref(false)

const tickets = mkList<CustomerTicket>()
const ticketStatus = ref('')
const ticketCategory = ref('')
const templates = mkList<EmailTemplate>()
const tplType = ref('')
const tasks = mkList<EmailTask>()
const taskStatus = ref('')
const processReport = ref<Record<string, unknown> | null>(null)
const reviews = mkList<NegativeReview>()
const reviewStatus = ref('')
const reviewMaxRating = ref<number | string>('')
const solicitations = ref<ReviewSolicitation[]>([])
const rmas = mkList<Rma>()
const rmaStatus = ref('')

const modal = ref<ModalKey | null>(null)
const form = ref<Record<string, any>>({})
const confirmBox = ref<null | { title: string; detail: string; run: () => Promise<void> }>(null)

interface CursorList<T> {
  rows: Ref<T[]>
  cursor: Ref<string | null>
  truncated: Ref<boolean>
  loading: Ref<boolean>
}

function mkList<T>(): CursorList<T> {
  return {
    rows: ref<T[]>([]) as unknown as Ref<T[]>,
    cursor: ref<string | null>(null),
    truncated: ref(false),
    loading: ref(false)
  }
}

const pagerText = (list: CursorList<unknown>): string =>
  list.truncated.value ? `已加载 ${list.rows.value.length} 条 · 后端标记仍有下一页` : `已加载 ${list.rows.value.length} 条`

const truthy = (v: unknown) => v === true || v === 1 || v === '1'
const shop = () => refreshShop()
const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

const call = async <T>(label: string, fn: () => Promise<ApiResponse<T>>, apply: (v: T) => void): Promise<boolean> => {
  busy.value = true
  try {
    const res = await fn()
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '后端返回非 200'}`)
      return false
    }
    apply(res.data as T)
    return true
  } catch (e) {
    pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
    return false
  } finally {
    busy.value = false
  }
}

const loadList = async <T>(
  list: { rows: { value: T[] }; cursor: { value: string | null }; truncated: { value: boolean }; loading: { value: boolean } },
  label: string,
  fetcher: (cursor: string | undefined) => Promise<ApiResponse<T[]>>,
  append: boolean
) => {
  const shopId = shop()
  if (!shopId) return
  if (!append) {
    list.rows.value = []
    list.cursor.value = null
    errors.value = []
  }
  list.loading.value = true
  try {
    const res = await fetcher(append ? list.cursor.value ?? undefined : undefined)
    if (res?.code !== 200) {
      pushError(`${label}：${res?.message || '后端返回非 200'}`)
      return
    }
    const batch = Array.isArray(res.data) ? res.data : []
    list.rows.value = append ? [...list.rows.value, ...batch] : batch
    list.truncated.value = res._page ? res._page.truncated : false
    list.cursor.value = res._page ? res._page.nextCursor : null
  } catch (e) {
    pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
  } finally {
    list.loading.value = false
  }
}

/* ---------- 各分区加载 ---------- */
const loadTickets = (append = false) => loadList(tickets, '工单列表', cursor =>
  cust.listTickets(shop(), { status: ticketStatus.value || undefined, category: ticketCategory.value || undefined, size: 50, cursor }), append)

const loadTemplates = (append = false) => loadList(templates, '邮件模板', cursor =>
  cust.listTemplates(shop(), { templateType: tplType.value || undefined, size: 50, cursor }), append)

const loadTasks = (append = false) => loadList(tasks, '邮件任务', cursor =>
  cust.listEmailTasks(shop(), { status: taskStatus.value || undefined, size: 50, cursor }), append)

const loadReviews = (append = false) => loadList(reviews, '差评列表', cursor =>
  cust.listNegativeReviews(shop(), {
    status: reviewStatus.value || undefined,
    minRating: reviewMaxRating.value === '' ? undefined : Number(reviewMaxRating.value),
    size: 50, cursor
  }), append)

const loadRmas = (append = false) => loadList(rmas, 'RMA 列表', cursor =>
  cust.listRmas(shop(), { status: rmaStatus.value || undefined, size: 50, cursor }), append)

const loadSolicitations = () => call('索评记录', () => cust.listSolicitations(shop(), { size: 50 }),
  (rows) => { solicitations.value = rows || [] })

/** 「按事件触发」的事件项：来自启用中的模板，不在前端编枚举 */
const eventOptions = computed(() => Array.from(new Set(
  templates.rows.value.filter(t => truthy(t.enabled) && t.triggerEvent).map(t => t.triggerEvent as string)
)))

/* ---------- 动作 ---------- */
const toggleTpl = async (tp: EmailTemplate, enabled: boolean) => {
  const ok = await call(enabled ? '启用模板' : '停用模板', () => cust.toggleTemplate(tp.id as number, enabled),
    () => undefined)
  if (ok) await loadTemplates()
}

const processConfirm = {
  title: '处理待发邮件队列',
  detail: '会遍历本店 PENDING 任务并尝试发送。邮件通道目前未接入：非模拟环境下任务会被标成 FAILED 并写原因，'
    + '模拟环境是「模拟发送成功」而不是真发信。确认你所在的环境后再执行。',
  run: async () => {
    const ok = await call('处理待发队列', () => cust.processPendingEmails(shop()), (r) => { processReport.value = r as Record<string, unknown> })
    if (ok) await Promise.all([loadTasks(), loadReviews()])
  }
}

const solicitConfirm = {
  title: '批量索评',
  detail: '依赖 SP-API 的 Request a Review。非模拟环境后端会直接抛错（不会伪造 SENT）；'
    + '模拟环境会为 5 个订单造 SIMULATED-* 记录。',
  run: async () => {
    const ok = await call('批量索评', () => cust.solicitReviews(shop()), () => undefined)
    if (ok) await loadSolicitations()
  }
}

const matchConfirm = (r: NegativeReview) => ({
  title: '匹配差评到订单',
  detail: '非模拟环境会抛错（不猜订单）；模拟环境写入 SIMULATED-MATCH-* 假订单号，只用于演示流程。',
  run: async () => {
    const ok = await call('匹配订单', () => cust.matchReviewToOrder(r.id as number), () => undefined)
    if (ok) await loadReviews()
  }
})

const followUp = async (r: NegativeReview) => {
  const ok = await call('生成跟进邮件', () => cust.followUpNegativeReview(r.id as number), () => undefined)
  if (ok) {
    await Promise.all([loadReviews(), loadTasks()])
  }
}

const changeRmaStatus = async (r: Rma, ev: Event) => {
  const status = (ev.target as HTMLSelectElement).value
  if (!status || status === r.status) return
  const ok = await call(`RMA → ${status}`, () => cust.updateRmaStatus(r.id as number, status), () => undefined)
  if (ok) await loadRmas()
}

/* ---------- 弹窗 ---------- */
const DEFAULTS: Record<ModalKey, () => Record<string, any>> = {
  ticket: () => ({ channel: 'MESSAGE', priority: 'NORMAL' }),
  reply: () => ({ reply: '' }),
  template: () => ({ enabled: 1, language: 'en', triggerDelayHours: 0, templateType: 'AFTERSALE' }),
  manualTask: () => ({ templateId: null }),
  trigger: () => ({ eventType: '' }),
  review: () => ({ reviewRating: 2, verifiedPurchase: 1 }),
  rma: () => ({ returnType: 'REFUND', productCondition: 'OPENED' })
}

const openModal = (key: ModalKey, seed: Record<string, any> = {}) => {
  const base = DEFAULTS[key]()
  if (key === 'template' && seed.template) {
    const t = seed.template as EmailTemplate
    base.id = t.id
    base.templateName = t.templateName
    base.templateType = t.templateType
    base.language = t.language
    base.triggerEvent = t.triggerEvent
    base.triggerDelayHours = t.triggerDelayHours
    base.enabled = t.enabled
    base.subject = t.subject
    base.body = t.body
  }
  form.value = { ...base, ...seed }
  modal.value = key
}

const submitModal = async () => {
  const key = modal.value
  if (!key) return
  const shopId = shop()
  const f = { ...form.value }
  let ok = false

  if (key === 'ticket') {
    if (!f.amazonOrderId?.trim() || !f.content?.trim()) { pushError('新建工单：订单号与内容必填'); return }
    ok = await call('新建工单', () => cust.receiveTicket({ ...f, shopId }), () => undefined)
    if (ok) await loadTickets()
  } else if (key === 'reply') {
    if (!f.reply?.trim()) { pushError('回复：内容必填'); return }
    ok = await call('回复工单', () => cust.replyTicket(f.ticket.id as number, f.reply.trim()), () => undefined)
    if (ok) await loadTickets()
  } else if (key === 'template') {
    if (!f.templateName?.trim() || !f.body?.trim()) { pushError('模板：名称与正文必填'); return }
    const body: Record<string, any> = { ...f, shopId }
    // 编辑态回填用的辅助键不进请求体
    delete body.id
    delete body.template
    ok = await call(f.id ? '更新模板' : '新建模板', () =>
      (f.id ? cust.updateTemplate(f.id as number, body) : cust.createTemplate(body)), () => undefined)
    if (ok) await loadTemplates()
  } else if (key === 'manualTask') {
    if (!f.amazonOrderId?.trim() || !f.buyerEmail?.trim() || !f.body?.trim()) {
      pushError('手动任务：订单号、收件人邮箱、正文必填'); return
    }
    ok = await call('创建邮件任务', () => cust.createManualTask({ ...f, shopId, source: 'MANUAL' }), () => undefined)
    if (ok) await loadTasks()
  } else if (key === 'trigger') {
    if (!f.eventType || !f.orderId?.trim()) { pushError('按事件触发：事件与订单号必填'); return }
    ok = await call('按事件触发邮件', () => cust.triggerEmail(shopId, {
      eventType: f.eventType, orderId: f.orderId.trim(), asin: f.asin || undefined,
      buyerEmail: f.buyerEmail || undefined, buyerName: f.buyerName || undefined, trackingNo: f.trackingNo || undefined
    }), () => undefined)
    if (ok) await loadTasks()
  } else if (key === 'review') {
    if (!f.asin?.trim()) { pushError('登记差评：ASIN 必填'); return }
    ok = await call('登记差评', () => cust.saveNegativeReview({
      ...f, shopId, reviewRating: Number(f.reviewRating), verifiedPurchase: Number(f.verifiedPurchase)
    }), () => undefined)
    if (ok) await loadReviews()
  } else if (key === 'rma') {
    if (!f.amazonOrderId?.trim() || !f.returnReason?.trim()) { pushError('RMA：订单号与退货原因必填'); return }
    ok = await call('新建 RMA', () => cust.createRma({ ...f, shopId }), () => undefined)
    if (ok) await loadRmas()
  }

  if (ok) modal.value = null
}

const confirm = (box: { title: string; detail: string; run: () => Promise<void> }) => { confirmBox.value = box }
const runConfirm = async () => {
  const box = confirmBox.value
  if (!box) return
  confirmBox.value = null
  await box.run()
}

/* ---------- 展示归类 ---------- */
const ticketClass = (s?: string) =>
  s === 'RESOLVED' ? 'healthy' : s === 'ESCALATED' ? 'urgent' : s === 'PENDING' ? 'risk' : ''
const sentimentClass = (s?: string) => (s === 'ANGRY' || s === 'NEGATIVE' ? 'urgent' : s === 'POSITIVE' ? 'healthy' : '')
const taskClass = (s?: string) => (s === 'SENT' ? 'healthy' : s === 'FAILED' ? 'urgent' : s === 'PENDING' ? 'risk' : '')
const reviewClass = (s?: string) => (s === 'RESOLVED' ? 'healthy' : s === 'IGNORED' ? 'unknown' : 'risk')
const rmaClass = (s?: string) => (s === 'PROCESSED' ? 'healthy' : s === 'CANCELLED' ? 'urgent' : s === 'PENDING' ? 'risk' : '')

const TAB_LOADERS: Record<TabKey, () => Promise<unknown>> = {
  ticket: () => loadTickets(),
  template: () => loadTemplates(),
  task: async () => { await Promise.all([loadTasks(), loadTemplates()]) },
  review: async () => { await Promise.all([loadReviews(), loadSolicitations()]) },
  rma: () => loadRmas()
}
const loaded = new Set<TabKey>()
const gotoTab = async (key: TabKey) => {
  tab.value = key
  if (loaded.has(key)) return
  loaded.add(key)
  await TAB_LOADERS[key]()
}

onMounted(async () => {
  if (!shop()) return
  await gotoTab('ticket')
})
</script>

<style scoped>
.customer-page { background: var(--color-background); }
.error-zone, .notice-zone { border-radius: var(--radius-md); padding: 0.625rem 0.875rem; margin-bottom: 1rem; font-size: 0.875rem; }
.error-zone { display: flex; align-items: center; gap: 0.5rem; background: var(--color-light-red); color: var(--color-error); }
.notice-zone { background: var(--color-warning-light); color: var(--color-warning-dark); line-height: 1.6; }
.tabs { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.tab { background: var(--color-surface); color: var(--color-muted); border: 1px solid var(--color-border); border-radius: var(--radius-md); padding: 0.4rem 0.875rem; font-size: 0.875rem; cursor: pointer; }
.tab.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.filter-row { display: flex; align-items: center; gap: 0.75rem; padding: 0.75rem 1rem 0; flex-wrap: wrap; }
.filter { font-size: 0.8125rem; color: var(--color-muted); display: inline-flex; align-items: center; gap: 0.375rem; }
.filter input, .filter select, .cell-input { padding: 0.3rem 0.5rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-size: 0.8125rem; }
.muted { color: var(--color-muted); font-size: 0.75rem; }
.neg { color: var(--color-error); font-weight: 600; }
.reason { font-size: 0.75rem; color: var(--color-error); max-width: 16rem; }
.cell-clip { max-width: 14rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.block-title { font-weight: 600; font-size: 0.9375rem; color: var(--color-on-surface); padding: 0.75rem 1rem 0.25rem; }
.table-card { margin-bottom: 1rem; }
.table-pager { display: flex; align-items: center; justify-content: space-between; padding: 0.75rem 1rem; }
.status-tag { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); font-size: 0.75rem; white-space: nowrap; }
.status-tag.healthy { background: var(--color-primary-light); color: var(--color-success); }
.status-tag.risk { background: var(--color-warning-light); color: var(--color-warning-dark); }
.status-tag.urgent { background: var(--color-light-red); color: var(--color-error); }
.status-tag.unknown { background: var(--color-muted-light); color: var(--color-muted); }
.action-btn { padding: 0.25rem 0.625rem; background: var(--color-primary-light); color: var(--color-primary); border: none; border-radius: var(--radius-sm); cursor: pointer; font-size: 0.75rem; margin-right: 0.25rem; }
.action-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.action-btn.danger { background: var(--color-light-red); color: var(--color-error); }
.page-btn { padding: 0.3rem 0.7rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); cursor: pointer; font-size: 0.8125rem; }
.modal-mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 2000; }
.modal { background: var(--color-surface); border-radius: var(--radius-md); padding: 1.5rem; width: 90%; max-width: 620px; max-height: 90vh; overflow-y: auto; }
.modal h3 { margin: 0 0 0.5rem; font-size: 1.125rem; color: var(--color-on-surface); }
.modal-note { font-size: 0.75rem; color: var(--color-muted); margin: 0 0 1rem; line-height: 1.5; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0.75rem; }
.form-grid label { display: flex; flex-direction: column; gap: 0.25rem; font-size: 0.8125rem; color: var(--color-on-surface); }
.form-grid .span2 { grid-column: 1 / -1; }
.form-grid input, .form-grid select, .form-grid textarea { padding: 0.5rem 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-md); font-size: 0.875rem; background: var(--color-background); color: var(--color-on-surface); }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }
.confirm-detail { font-size: 0.875rem; color: var(--color-on-surface); line-height: 1.5; }
@media (max-width: 768px) { .form-grid { grid-template-columns: 1fr; } }
</style>
