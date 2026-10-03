<template>
  <div class="connector-page">
    <AppHeader />
    <AppSidebar />
    <main class="main-content">
      <div class="hero-section">
        <h1 class="hero-title">连接器状态</h1>
        <p class="hero-subtitle">以能力清单、A1–A8 证据和分层自检为准；模拟结果不代表平台真实连通。</p>
      </div>

      <div class="truth-note" role="note">
        <Icon icon="mdi:shield-check-outline" width="20" />
        <span>除管理员显式保存/删除店铺 SP-API 凭证外，本页不修改业务数据；密文只写不回显。分层自检会触发出网，<code>forceTokenRefresh=true</code> 还会失效 token 缓存。只有 <strong>reachable=true</strong> 且证据达标时，才允许宣称“已接通”；没有真实凭证与联调证据时保持“具备对接能力（未联调）”。</span>
      </div>

      <div v-if="loading" class="loading-card" role="status" aria-label="连接器状态加载中">
        <div class="skeleton sk-line sk-line-sm"></div>
        <div class="skeleton sk-line sk-line-lg"></div>
        <div class="skeleton sk-block"></div>
      </div>

      <div v-else-if="loadError" class="error-card" role="alert">
        <Icon icon="mdi:alert-circle-outline" width="22" />
        <div>
          <strong>能力清单加载失败</strong>
          <p>{{ loadError }}</p>
        </div>
        <button class="secondary-btn" type="button" @click="loadConnectors">重试</button>
      </div>

      <div v-else-if="connectors.length === 0" class="empty-state card-surface">
        <Icon icon="mdi:connection" width="36" class="empty-icon" />
        <span>暂无连接器能力清单</span>
      </div>

      <!-- SP-API 进程自描述 + 操作目录（GET /spapi/status、GET /spapi/operations） -->
      <section class="selfcheck-card card-surface" data-panel="selfcheck">
        <div class="section-title-row">
          <div>
            <h3>SP-API 进程自检</h3>
            <p class="section-sub">
              验收约束 C1「被测服务必须以 prod 档启动，违反则整份记录作废」以前只能人工声明；
              下面几个字段是它在进程外唯一的凭据。只报 profile、布尔开关与凭证条数，不含任何密文。
            </p>
          </div>
          <button class="secondary-btn" type="button" :disabled="statusLoading" @click="loadSelfDescription">
            {{ statusLoading ? '加载中...' : '刷新' }}
          </button>
        </div>

        <div v-if="statusError" class="inline-error" role="alert">
          <span>{{ statusError }}</span>
          <button class="link-btn" type="button" @click="loadSelfDescription">重试</button>
        </div>
        <div v-else-if="selfDescription" class="kv-grid">
          <div class="kv">
            <span class="k">生效 profile</span>
            <span class="v mono">{{ selfDescription.profile || '未声明' }}</span>
          </div>
          <div class="kv">
            <span class="k">mock 客户端</span>
            <span class="v" :class="selfDescription.mockClientsActive ? 'flag-mock' : 'flag-real'">
              {{ boolText(selfDescription.mockClientsActive) }}
            </span>
          </div>
          <div class="kv">
            <span class="k">启动自检已执行</span><span class="v">{{ boolText(selfDescription.startupCheckRan) }}</span>
          </div>
          <div class="kv">
            <span class="k">自检要求凭证</span><span class="v">{{ boolText(selfDescription.startupRequireCredentials) }}</span>
          </div>
          <div class="kv"><span class="k">已加载凭证</span><span class="v">{{ credentialCountText }}</span></div>
        </div>
        <p v-if="mockWarning" class="mock-warning" role="status">{{ mockWarning }}</p>

        <div class="section-title-row catalog-head">
          <div>
            <h3>可调用操作目录（共 {{ operations.length }} 条）</h3>
            <p class="section-sub">
              静态目录，读取不需要店铺凭据。本页不提供执行：POST /spapi/operations/{operationId}
              要已存凭证，且它的 @ShopScoped 因路径没有 Long shopId 参数而实测不生效，
              真正的守卫是服务内显式的店铺归属判定——把通用执行器摊成按钮，
              等于给每个登录用户一个任意 API 调用面板。
            </p>
          </div>
          <button class="secondary-btn" type="button" :disabled="opsLoading" @click="loadOperationCatalog">
            {{ opsLoading ? '加载中...' : '刷新' }}
          </button>
        </div>
        <div v-if="opsError" class="inline-error" role="alert">
          <span>{{ opsError }}</span>
          <button class="link-btn" type="button" @click="loadOperationCatalog">重试</button>
        </div>
        <div v-else-if="!operations.length" class="inline-empty">目录为空或尚未加载</div>
        <div v-else class="table-wrap">
          <table class="data-table">
            <thead>
              <tr><th>operationId</th><th>家族</th><th>方法</th><th>路径</th><th>必填参数</th><th>免授权</th></tr>
            </thead>
            <tbody>
              <tr v-for="op in shownOperations" :key="op.operationId">
                <td class="mono">{{ op.operationId }}</td>
                <td>{{ op.family || '-' }}</td>
                <td class="mono">{{ op.method || '-' }}</td>
                <td class="mono path-cell" :title="op.path || ''">{{ op.path || '-' }}</td>
                <td class="params-cell">{{ requiredText(op) }}</td>
                <td>{{ boolText(op.grantless) }}</td>
              </tr>
            </tbody>
          </table>
          <button v-if="operations.length > OPS_PAGE" class="link-btn" type="button" @click="showAllOps = !showAllOps">
            {{ showAllOps ? `只看前 ${OPS_PAGE} 条` : `展开全部 ${operations.length} 条` }}
          </button>
        </div>
      </section>

      <section v-for="connector in connectors" :key="connector.code" class="connector-card card-surface">
        <header class="connector-head">
          <div>
            <div class="connector-title-row">
              <h2>{{ connector.name }}</h2>
              <span class="code-chip">{{ connector.code }}</span>
            </div>
            <p class="connector-subtitle">配置 profile：{{ connector.profile || '未声明' }}</p>
          </div>
          <span class="mode-pill" :class="connector.mockActive ? 'mode-mock' : 'mode-real'">
            {{ clientMode(connector) }}
          </span>
        </header>

        <div class="status-banner" :class="statusClass(connector)">
          <Icon :icon="statusIcon(connector)" width="22" />
          <div>
            <strong>{{ statusText(connector) }}</strong>
            <p v-if="hasStatusConflict(connector)">状态异常：证据等级与联通声明冲突。请先修复后端能力清单，禁止按已接通处理。</p>
            <p v-else-if="!connector.reachable">当前只有代码与契约能力，尚无平台真实连通证据；有凭证并完成联调后才会变为“已接通”。</p>
            <p v-else>平台连通证据已记录；请同时核对 A1–A8 逐项等级和最近一次自检结果。</p>
          </div>
        </div>

        <div class="metrics-grid">
          <div class="metric">
            <span>凭证来源</span>
            <strong>{{ credentialSourceText(connector) }}</strong>
          </div>
          <div class="metric">
            <span>已加载凭证</span>
            <strong>{{ connector.credentialCount }} 条</strong>
          </div>
          <div class="metric">
            <span>能力覆盖</span>
            <strong>{{ connector.implementedCount }} 已实现 / {{ connector.notImplementedCount }} 未实现</strong>
          </div>
          <div class="metric">
            <span>整体证据</span>
            <strong>{{ evidenceText(connector.evidenceLevel) }}</strong>
          </div>
          <div class="metric">
            <span>最近自检</span>
            <strong>{{ lastResultText(connector.lastResult) }}</strong>
            <small v-if="connector.lastCallAt">{{ formatTime(connector.lastCallAt) }}</small>
            <small v-else>尚无调用记录</small>
          </div>
          <div class="metric">
            <span>最近结果码</span>
            <strong class="mono">{{ connector.lastOutcomeCode || 'NOT_RUN' }}</strong>
          </div>
        </div>

        <div v-if="connector.code === 'spapi'" class="credential-block">
          <div class="section-title-row">
            <div>
              <h3>SP-API 加密凭证</h3>
              <p class="muted">密钥加密保存，只写不回显；凭证已保存，仍需通过真实 LWA/只读 API 自检。</p>
            </div>
            <span class="credential-state-pill" :class="credentialConfigured ? 'credential-state-ok' : 'credential-state-missing'">
              {{ credentialLoading ? '读取中' : credentialConfigured ? '已配置' : '尚未配置' }}
            </span>
          </div>

          <p v-if="!currentShopId" class="inline-warning">请先在右上角选择店铺，再管理 SP-API 凭证。</p>
          <template v-else>
            <div v-if="credentialState.data" class="credential-flags">
              <span :class="credentialState.data?.clientIdConfigured ? 'flag-ok' : 'flag-missing'">clientId {{ credentialState.data?.clientIdConfigured ? '已配置' : '未配置' }}</span>
              <span :class="credentialState.data?.clientSecretConfigured ? 'flag-ok' : 'flag-missing'">clientSecret {{ credentialState.data?.clientSecretConfigured ? '已配置' : '未配置' }}</span>
              <span :class="credentialState.data?.refreshTokenConfigured ? 'flag-ok' : 'flag-missing'">refreshToken {{ credentialState.data?.refreshTokenConfigured ? '已配置' : '未配置' }}</span>
              <span :class="credentialState.data?.awsKeysConfigured ? 'flag-ok' : 'flag-missing'">AWS 密钥 {{ credentialState.data?.awsKeysConfigured ? '已配置' : '未配置' }}</span>
              <span v-if="credentialState.data?.updatedAt">更新时间：{{ formatTime(credentialState.data?.updatedAt) }}</span>
            </div>
            <p v-if="credentialState.message" class="credential-message" :class="credentialState.responseOk ? 'message-ok' : 'message-error'">
              {{ credentialState.message }}
            </p>

            <form data-testid="credential-form" class="credential-form" autocomplete="off" @submit.prevent="saveCredential(connector)">
              <label class="credential-field">
                <span>LWA clientId</span>
                <input data-testid="credential-client-id" v-model.trim="credentialForm.clientId" autocomplete="off" placeholder="amzn1.application-oa2-client..." />
              </label>
              <label class="credential-field">
                <span>LWA clientSecret</span>
                <input data-testid="credential-client-secret" v-model.trim="credentialForm.clientSecret" type="password" autocomplete="new-password" placeholder="留空保留旧值" />
              </label>
              <label class="credential-field">
                <span>LWA refreshToken</span>
                <input data-testid="credential-refresh-token" v-model.trim="credentialForm.refreshToken" type="password" autocomplete="new-password" placeholder="留空保留旧值" />
              </label>
              <label class="credential-field">
                <span>region</span>
                <input data-testid="credential-region" v-model.trim="credentialForm.region" autocomplete="off" placeholder="NA / EU / FE" />
              </label>
              <label class="credential-field">
                <span>marketplaceId</span>
                <input data-testid="credential-marketplace-id" v-model.trim="credentialForm.marketplaceId" autocomplete="off" placeholder="ATVPDKIKX0DER" />
              </label>
              <label class="credential-field">
                <span>sellerId</span>
                <input data-testid="credential-seller-id" v-model.trim="credentialForm.sellerId" autocomplete="off" placeholder="卖家 ID（可选）" />
              </label>
              <label class="credential-field">
                <span>AWS accessKey</span>
                <input data-testid="credential-access-key" v-model.trim="credentialForm.accessKey" type="password" autocomplete="new-password" placeholder="需要签名时可留空" />
              </label>
              <label class="credential-field">
                <span>AWS secretKey</span>
                <input data-testid="credential-secret-key" v-model.trim="credentialForm.secretKey" type="password" autocomplete="new-password" placeholder="需要签名时可留空" />
              </label>
              <label v-if="credentialState.data?.awsKeysConfigured" class="credential-clear">
                <input v-model="credentialForm.clearAwsKeys" type="checkbox" />
                清空已有 AWS 签名密钥
              </label>
              <div class="credential-actions">
                <p class="credential-helper">提交留空字段会保留旧值；密文保存成功后立即从本页输入框清除，不会回填。</p>
                <button
                  v-if="credentialState.data?.configured"
                  data-testid="credential-delete"
                  class="secondary-btn danger-btn"
                  type="button"
                  :disabled="credentialDeleting || credentialSaving"
                  @click="deleteCredential(connector)"
                >
                  {{ credentialDeleting ? '删除中…' : '删除凭证' }}
                </button>
                <button class="primary-btn" type="submit" :disabled="credentialSaving || credentialDeleting">
                  {{ credentialSaving ? '保存中…' : '保存加密凭证' }}
                </button>
              </div>
            </form>
          </template>
        </div>
        <div class="criteria-block">
          <div class="section-title-row">
            <h3>A1–A8 证据等级</h3>
            <span class="muted">整体等级取最弱一环</span>
          </div>
          <div class="criteria-grid">
            <div v-for="[key, value] in criteriaEntries(connector)" :key="key" class="criterion">
              <span>{{ key }}</span>
              <strong>{{ evidenceText(value) }}</strong>
            </div>
          </div>
          <p v-if="connector.blockerSummary" class="blocker-summary">
            未达标：{{ connector.blockerSummary }}
          </p>
        </div>

        <div class="operations-block">
          <div class="section-title-row">
            <h3>能力明细</h3>
            <span class="muted">{{ connector.operations.length }} 项官方能力清单</span>
          </div>
          <div class="table-wrap">
            <table class="data-table connector-table">
              <thead>
                <tr>
                  <th>Operation</th>
                  <th>路径 / 标识</th>
                  <th>状态</th>
                  <th>实现或缺口说明</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="operation in connector.operations" :key="operation.id">
                  <td class="mono">{{ operation.id }}</td>
                  <td class="mono path-cell">{{ operation.path }}</td>
                  <td>
                    <span class="status-tag" :class="operation.status === 'IMPLEMENTED' ? 'tag-implemented' : 'tag-missing'">
                      {{ operation.status === 'IMPLEMENTED' ? '已实现' : '未实现' }}
                    </span>
                  </td>
                  <td>{{ operation.note }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="self-test-block">
          <div class="section-title-row">
            <div>
              <h3>只读自检</h3>
              <p class="muted">调用 orders.getOrders 验证当前店铺的 LWA、凭证、限流与只读链路；不会回传订单明细。</p>
            </div>
            <button
              class="primary-btn"
              type="button"
              :disabled="!canSelfTest(connector)"
              @click="runSelfTest(connector)"
            >
              {{ testingCode === connector.code ? '自检执行中…' : '运行只读自检' }}
            </button>
          </div>

          <p v-if="!currentShopId" class="inline-warning">请先在右上角选择店铺，再运行自检。</p>
          <p v-else-if="connector.code !== 'spapi'" class="inline-warning">当前后端仅支持 SP-API 只读自检。</p>

          <div v-if="selfTests[connector.code]" class="self-test-result" :class="selfTestResultClass(connector)">
            <div class="result-head">
              <Icon :icon="selfTestResultIcon(connector)" width="20" />
              <strong>{{ selfTestTitle(connector) }}</strong>
              <span v-if="selfTests[connector.code].data.outcomeCode" class="code-chip">
                {{ selfTests[connector.code].data.outcomeCode }}
              </span>
            </div>
            <p>{{ selfTestDetail(connector) }}</p>
            <div class="result-meta">
              <span>operation：{{ selfTests[connector.code].data.operation || 'unknown' }}</span>
              <span>耗时：{{ selfTests[connector.code].data.elapsedMs }} ms</span>
              <span>条目数：{{ selfTests[connector.code].data.itemCount }}</span>
              <span v-if="selfTests[connector.code].data.at">时间：{{ formatTime(selfTests[connector.code].data.at) }}</span>
            </div>
          </div>
        </div>

        <div class="preflight-block">
          <div class="section-title-row">
            <div>
              <h3>分层连通性自检</h3>
              <p class="muted">按 CREDENTIAL → LWA_TOKEN → READ_API 逐层定位；SKIP 表示该阶段未执行，不能当作通过。</p>
            </div>
            <div class="preflight-actions">
              <label class="preflight-option">
                <input
                  v-model="forceTokenRefresh[connector.code]"
                  type="checkbox"
                  :disabled="preflightCode === connector.code"
                />
                强制刷新 LWA token
              </label>
              <button
                class="primary-btn"
                type="button"
                :disabled="!canPreflight(connector)"
                @click="runPreflight(connector)"
              >
                {{ preflightCode === connector.code ? '分层自检执行中…' : '运行分层连通性自检' }}
              </button>
            </div>
          </div>

          <p v-if="!currentShopId" class="inline-warning">请先在右上角选择店铺，再运行分层自检。</p>
          <p v-else-if="connector.code !== 'spapi'" class="inline-warning">当前后端仅支持 SP-API 分层连通性自检。</p>

          <div
            v-if="preflights[connector.code]"
            class="preflight-result"
            :class="preflightResultClass(connector)"
          >
            <div class="result-head">
              <Icon :icon="preflightResultIcon(connector)" width="20" />
              <strong>{{ preflightTitle(connector) }}</strong>
              <span v-if="preflightData(connector)" class="code-chip">
                {{ preflightData(connector)?.ready ? 'READY' : 'NOT_READY' }}
              </span>
            </div>
            <p>{{ preflightDetail(connector) }}</p>
            <div v-if="preflightData(connector)" class="result-meta">
              <span>shopId：{{ preflightData(connector)?.shopId }}</span>
              <span>region：{{ preflightData(connector)?.region || '未知' }}</span>
              <span>marketplace：{{ preflightData(connector)?.marketplaceId || '未登记' }}</span>
              <span>host：{{ preflightData(connector)?.host || '未解析' }}</span>
              <span>endpointOverridden：{{ preflightData(connector)?.endpointOverridden ? 'true' : 'false' }}</span>
              <span v-if="preflightData(connector)?.checkedAt">时间：{{ formatTime(preflightData(connector)?.checkedAt || '') }}</span>
            </div>

            <div v-if="preflightData(connector)" class="preflight-stages">
              <article
                v-for="stage in (preflightData(connector)?.stages || [])"
                :key="stage.name"
                class="preflight-stage"
                :class="preflightStageClass(stage.status)"
              >
                <div class="stage-head">
                  <span class="stage-name">{{ stage.name }}</span>
                  <span class="stage-status">{{ stageStatusText(stage.status) }}</span>
                </div>
                <p v-if="stage.detail" class="stage-detail">{{ stage.detail }}</p>
                <dl class="stage-meta">
                  <div><dt>durationMs</dt><dd>{{ stage.durationMs }}</dd></div>
                  <div v-if="stage.errorCode"><dt>errorCode</dt><dd>{{ stage.errorCode }}</dd></div>
                  <div v-if="stage.platformStatus !== null"><dt>platformStatus</dt><dd>{{ stage.platformStatus }}</dd></div>
                </dl>
                <p v-if="stage.remediation" class="stage-remediation">下一步：{{ stage.remediation }}</p>
              </article>
            </div>

            <p v-if="preflightData(connector)?.nextAction" class="preflight-next-action">
              建议操作：{{ preflightData(connector)?.nextAction }}
            </p>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import {
  deleteConnectorCredential,
  getConnectorCredentialStatus,
  getSpapiStatus,
  listConnectors,
  listSpapiOperations,
  preflightConnector,
  saveConnectorCredential,
  selfTestConnector,
  CREDENTIAL_COUNT_UNKNOWN,
  type ConnectorCapability,
  type ConnectorCredentialStatus,
  type ConnectorCredentialUpdate,
  type ConnectorPreflightReport,
  type ConnectorPreflightStageStatus,
  type ConnectorSelfTestData,
  type SpapiSelfDescription,
  type SpApiOperationSpec
} from '@/api/connectors'
import { useShopGuard } from '@/composables/useShopGuard'

interface CredentialForm {
  clientId: string
  clientSecret: string
  refreshToken: string
  accessKey: string
  secretKey: string
  region: string
  marketplaceId: string
  sellerId: string
  clearAwsKeys: boolean
}

interface CredentialUiState {
  data?: ConnectorCredentialStatus
  responseOk: boolean
  message: string
}

const emptyCredentialForm = (): CredentialForm => ({
  clientId: '',
  clientSecret: '',
  refreshToken: '',
  accessKey: '',
  secretKey: '',
  region: '',
  marketplaceId: '',
  sellerId: '',
  clearAwsKeys: false
})

interface SelfTestState {
  data: ConnectorSelfTestData
  responseOk: boolean
  message: string
}

interface PreflightState {
  data?: ConnectorPreflightReport
  responseOk: boolean
  message: string
}

const { currentShopId, refreshShop } = useShopGuard()
const connectors = ref<ConnectorCapability[]>([])
const selfTests = ref<Record<string, SelfTestState>>({})
const preflights = ref<Record<string, PreflightState>>({})
const forceTokenRefresh = ref<Record<string, boolean>>({})
const credentialForm = ref<CredentialForm>(emptyCredentialForm())
const credentialState = ref<CredentialUiState>({ responseOk: true, message: '' })
const credentialLoading = ref(false)
const credentialSaving = ref(false)
const credentialDeleting = ref(false)
const loading = ref(true)
const loadError = ref('')
const testingCode = ref('')
const preflightCode = ref('')

const evidenceLabels: Record<string, string> = {
  E0: 'E0 未验证',
  E1: 'E1 仓库自证',
  E2: 'E2 离线测试',
  E3: 'E3 契约锁定',
  E4: 'E4 真实联调',
  E5: 'E5 生产观测'
}

const isMockMode = (connector: ConnectorCapability) => {
  if (connector.mockActive) return true
  return connector.profile?.split(',').map((item) => item.trim()).includes('mock') ?? false
}

const clientMode = (connector: ConnectorCapability) => {
  return isMockMode(connector) ? '模拟客户端模式' : '真实客户端模式'
}

const credentialSourceText = (connector: ConnectorCapability) => {
  if (connector.credentialSource === 'db') return '数据库凭证库'
  if (connector.credentialSource === 'env') return '环境变量'
  if (connector.credentialSource === 'vault') return '密钥管理服务'
  return '未配置'
}

const evidenceText = (level?: string) => evidenceLabels[level || ''] || `${level || 'E0'} 未验证`

const lastResultText = (result: string) => {
  if (result === 'SUCCESS') return '成功'
  if (result === 'FAILED') return '失败'
  return '从未执行'
}

const formatTime = (value: string) => {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

const criteriaEntries = (connector: ConnectorCapability): [string, string][] => {
  return Object.entries(connector.criteria || {}).sort(([a], [b]) => a.localeCompare(b))
}

const hasStatusConflict = (connector: ConnectorCapability) => {
  const claimsReachable = connector.displayText.includes('已接通') || connector.displayText.includes('API-Ready')
  return !connector.reachable && claimsReachable
}

const statusText = (connector: ConnectorCapability) => {
  if (hasStatusConflict(connector)) return '状态异常：证据等级与联通声明冲突'
  if (!connector.enabled) return '连接器未启用'
  return connector.displayText || '具备对接能力（未联调）'
}

const statusClass = (connector: ConnectorCapability) => {
  if (hasStatusConflict(connector) || !connector.enabled) return 'status-error'
  if (connector.reachable) return 'status-success'
  return 'status-pending'
}

const statusIcon = (connector: ConnectorCapability) => {
  if (hasStatusConflict(connector) || !connector.enabled) return 'mdi:alert-circle-outline'
  if (connector.reachable) return 'mdi:check-circle-outline'
  return 'mdi:progress-clock'
}

const canSelfTest = (connector: ConnectorCapability) => {
  return connector.code === 'spapi' && Boolean(currentShopId.value) && testingCode.value !== connector.code
}

const canPreflight = (connector: ConnectorCapability) => {
  return connector.code === 'spapi' && Boolean(currentShopId.value) && preflightCode.value !== connector.code
}

const errorMessage = (error: unknown) => {
  if (error && typeof error === 'object') {
    const candidate = error as { response?: { data?: { message?: string } }; message?: string }
    if (candidate.response?.data?.message) return candidate.response.data.message
    if (candidate.message) return candidate.message
  }
  return '请求失败，请检查网络或登录状态'
}

const credentialConfigured = computed(() => Boolean(credentialState.value.data?.configured))

const loadCredentialStatus = async () => {
  const shopId = Number(refreshShop())
  if (!Number.isFinite(shopId) || shopId <= 0) {
    credentialState.value = { responseOk: true, message: '' }
    return
  }

  credentialLoading.value = true
  try {
    const response = await getConnectorCredentialStatus(shopId)
    if (response.code !== 200 || !response.data) {
      throw new Error(response.message || '凭证状态返回格式异常')
    }
    credentialState.value = {
      data: response.data,
      responseOk: true,
      message: ''
    }
    credentialForm.value.region = response.data.region || ''
    credentialForm.value.marketplaceId = response.data.marketplaceId || ''
    credentialForm.value.sellerId = response.data.sellerId || ''
  } catch (error) {
    credentialState.value = {
      responseOk: false,
      message: errorMessage(error)
    }
  } finally {
    credentialLoading.value = false
  }
}

const saveCredential = async (connector: ConnectorCapability) => {
  const shopId = Number(refreshShop())
  if (!Number.isFinite(shopId) || shopId <= 0) {
    credentialState.value = { responseOk: false, message: '无店铺上下文：请先在右上角选择店铺' }
    return
  }

  const payload: ConnectorCredentialUpdate = {
    clientId: credentialForm.value.clientId,
    clientSecret: credentialForm.value.clientSecret,
    refreshToken: credentialForm.value.refreshToken,
    accessKey: credentialForm.value.accessKey,
    secretKey: credentialForm.value.secretKey,
    region: credentialForm.value.region,
    marketplaceId: credentialForm.value.marketplaceId,
    sellerId: credentialForm.value.sellerId,
    clearAwsKeys: credentialForm.value.clearAwsKeys
  }

  credentialSaving.value = true
  credentialState.value = { ...credentialState.value, responseOk: true, message: '' }
  let saved = false
  try {
    const response = await saveConnectorCredential(shopId, payload)
    if (response.code !== 200 || !response.data) {
      throw new Error(response.message || '凭证保存返回格式异常')
    }
    credentialState.value = {
      data: response.data,
      responseOk: true,
      message: '凭证已保存，仍需通过真实 LWA/只读 API 自检'
    }
    credentialForm.value.clientSecret = ''
    credentialForm.value.refreshToken = ''
    credentialForm.value.accessKey = ''
    credentialForm.value.secretKey = ''
    credentialForm.value.clearAwsKeys = false
    saved = true
  } catch (error) {
    credentialState.value = {
      ...credentialState.value,
      responseOk: false,
      message: errorMessage(error)
    }
  } finally {
    credentialSaving.value = false
  }

  if (saved) {
    await runPreflight(connector, true)
  }
}

const deleteCredential = async (connector: ConnectorCapability) => {
  const shopId = Number(refreshShop())
  if (!Number.isFinite(shopId) || shopId <= 0) {
    credentialState.value = { responseOk: false, message: '无店铺上下文：请先在右上角选择店铺' }
    return
  }
  if (!window.confirm('确认删除该店铺的 SP-API 凭证？删除后对应 LWA token 会立即失效。')) return

  credentialDeleting.value = true
  try {
    const response = await deleteConnectorCredential(shopId)
    if (response.code !== 200 || !response.data) {
      throw new Error(response.message || '凭证删除返回格式异常')
    }
    credentialState.value = { data: response.data, responseOk: true, message: '凭证已删除，LWA token 已失效' }
    credentialForm.value = emptyCredentialForm()
    delete preflights.value[connector.code]
    delete selfTests.value[connector.code]
  } catch (error) {
    credentialState.value = {
      ...credentialState.value,
      responseOk: false,
      message: errorMessage(error)
    }
  } finally {
    credentialDeleting.value = false
  }
}

// ===== SP-API 进程自描述与操作目录 =====
const OPS_PAGE = 20
const selfDescription = ref<SpapiSelfDescription | null>(null)
const statusLoading = ref(false)
const statusError = ref('')
const operations = ref<SpApiOperationSpec[]>([])
const opsLoading = ref(false)
const opsError = ref('')
const showAllOps = ref(false)

/** 后端拿不到启动自检快照时给的是 -1，含义是「未知」；显示成 0 条会被读成「一张凭证都没有」。 */
const credentialCountText = computed(() => {
  const n = selfDescription.value?.loadedCredentialCount
  if (n === undefined || n === null) return '未知'
  if (n === CREDENTIAL_COUNT_UNKNOWN) return '未知（启动自检未执行）'
  return `${n} 条`
})

const mockWarning = computed(() => (selfDescription.value?.mockClientsActive
  ? '当前进程启用了 mock 客户端：Reports / Finances / Fees 返回的是离线样例，据此产出的验收成功样例不成立。'
  : ''))

const boolText = (v?: boolean | null): string => (v === undefined || v === null ? '未知' : v ? '是' : '否')

const requiredText = (op: SpApiOperationSpec): string => {
  const parts = [...(op.requiredPathParameters || []), ...(op.requiredQueryParameters || [])]
  if (op.bodyRequired) parts.push('body')
  return parts.length ? parts.join('、') : '无'
}

const shownOperations = computed(() => (showAllOps.value ? operations.value : operations.value.slice(0, OPS_PAGE)))

/** 读失败时清空旧值：留着上一次读到的 profile，看起来就像当前进程的状态。 */
const loadSelfDescription = async () => {
  statusLoading.value = true
  statusError.value = ''
  try {
    const res = await getSpapiStatus()
    if (res?.code === 200 && res.data) {
      selfDescription.value = res.data
    } else {
      selfDescription.value = null
      statusError.value = `自检读取失败：${res?.message || '后端未返回成功'}`
    }
  } catch (e) {
    selfDescription.value = null
    statusError.value = `自检读取失败：${e instanceof Error ? e.message : '调用失败'}`
  } finally {
    statusLoading.value = false
  }
}

const loadOperationCatalog = async () => {
  opsLoading.value = true
  opsError.value = ''
  try {
    const res = await listSpapiOperations()
    if (res?.code === 200 && Array.isArray(res.data)) {
      operations.value = res.data
    } else {
      operations.value = []
      opsError.value = `目录读取失败：${res?.message || '后端未返回成功'}`
    }
  } catch (e) {
    operations.value = []
    opsError.value = `目录读取失败：${e instanceof Error ? e.message : '调用失败'}`
  } finally {
    opsLoading.value = false
  }
}

const loadConnectors = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const response = await listConnectors()
    if (response.code !== 200 || !Array.isArray(response.data)) {
      throw new Error(response.message || '能力清单返回格式异常')
    }
    connectors.value = response.data
    if (response.data.some((item) => item.code === 'spapi')) {
      await loadCredentialStatus()
    }
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

const runSelfTest = async (connector: ConnectorCapability) => {
  const shopId = Number(refreshShop())
  if (!Number.isFinite(shopId) || shopId <= 0) {
    selfTests.value[connector.code] = {
      data: {
        connector: connector.code,
        operation: 'orders.getOrders',
        ok: false,
        outcomeCode: 'SHOP_ID_MISSING',
        detail: '请先选择店铺',
        elapsedMs: 0,
        itemCount: 0,
        at: new Date().toISOString()
      },
      responseOk: false,
      message: '无店铺上下文：请先在右上角选择店铺'
    }
    return
  }

  testingCode.value = connector.code
  try {
    const response = await selfTestConnector(connector.code, shopId)
    const data = response.data || {
      connector: connector.code,
      operation: 'unknown',
      ok: false,
      outcomeCode: 'EMPTY_RESPONSE',
      detail: response.message || '后端未返回自检详情',
      elapsedMs: 0,
      itemCount: 0,
      at: new Date().toISOString()
    }
    selfTests.value[connector.code] = {
      data,
      responseOk: response.code === 200 && data.ok,
      message: response.message || ''
    }
  } catch (error) {
    selfTests.value[connector.code] = {
      data: {
        connector: connector.code,
        operation: 'orders.getOrders',
        ok: false,
        outcomeCode: 'REQUEST_FAILED',
        detail: errorMessage(error),
        elapsedMs: 0,
        itemCount: 0,
        at: new Date().toISOString()
      },
      responseOk: false,
      message: errorMessage(error)
    }
  } finally {
    testingCode.value = ''
  }
}

const runPreflight = async (connector: ConnectorCapability, forceRefresh = false) => {
  const shopId = Number(refreshShop())
  if (!Number.isFinite(shopId) || shopId <= 0) {
    preflights.value[connector.code] = {
      responseOk: false,
      message: '无店铺上下文：请先在右上角选择店铺'
    }
    return
  }

  preflightCode.value = connector.code
  try {
    const force = forceRefresh || Boolean(forceTokenRefresh.value[connector.code])
    const response = await preflightConnector(connector.code, shopId, force)
    if (!response.data) {
      preflights.value[connector.code] = {
        responseOk: false,
        message: response.message || '分层自检返回格式异常'
      }
      return
    }
    preflights.value[connector.code] = {
      data: response.data,
      responseOk: response.code === 200,
      message: response.message || ''
    }
  } catch (error) {
    preflights.value[connector.code] = {
      responseOk: false,
      message: errorMessage(error)
    }
  } finally {
    preflightCode.value = ''
  }
}

const preflightData = (connector: ConnectorCapability) => {
  return preflights.value[connector.code]?.data
}

const preflightTitle = (connector: ConnectorCapability) => {
  const state = preflights.value[connector.code]
  if (!state?.responseOk) return '分层自检请求失败'
  const data = state.data
  if (!data) return '分层自检无有效结果'
  if (!data.ready) return '分层自检未全部通过'
  if (isMockMode(connector)) return '模拟分层自检完成'
  if (data.endpointOverridden) return '非官方端点自检通过'
  return '当前只读链路已打通'
}

const preflightDetail = (connector: ConnectorCapability) => {
  const state = preflights.value[connector.code]
  if (!state) return ''
  if (!state.responseOk || !state.data) {
    return state.message || '分层自检请求失败，未获得可用报告。'
  }
  const data = state.data
  if (isMockMode(connector)) {
    return '模拟模式结果不代表真实 API 连通，不能作为联调或生产证据。'
  }
  if (data.ready && data.endpointOverridden) {
    return '非官方端点自检通过，仅 E2；即使三段全通过，也不得将其标记为真实联调。'
  }
  if (data.ready) {
    return '当前只读链路已打通；仅覆盖当前店铺的凭证、LWA 与只读 API，不代表写入、RDT、通知或生产 SLA 已联调。'
  }
  return '分层自检未全部通过；SKIP 表示该阶段未执行，不能当作通过。请按失败阶段和 nextAction 处理。'
}

const preflightResultClass = (connector: ConnectorCapability) => {
  const state = preflights.value[connector.code]
  if (!state?.responseOk) return 'result-error'
  return state.data?.ready ? 'result-success' : 'result-pending'
}

const preflightResultIcon = (connector: ConnectorCapability) => {
  const state = preflights.value[connector.code]
  if (!state?.responseOk) return 'mdi:alert-circle-outline'
  return state.data?.ready ? 'mdi:check-circle-outline' : 'mdi:progress-alert'
}

const preflightStageClass = (status: ConnectorPreflightStageStatus) => {
  if (status === 'PASS') return 'stage-pass'
  if (status === 'SKIP') return 'stage-skip'
  return 'stage-fail'
}

const stageStatusText = (status: ConnectorPreflightStageStatus) => {
  return status === 'SKIP' ? 'SKIP（未执行）' : status
}
const selfTestTitle = (connector: ConnectorCapability) => {
  const state = selfTests.value[connector.code]
  if (!state?.responseOk) return '自检未通过'
  return isMockMode(connector) ? '离线自检完成' : '只读自检完成'
}

const selfTestDetail = (connector: ConnectorCapability) => {
  const state = selfTests.value[connector.code]
  if (!state) return ''
  if (!state.responseOk) {
    const credentialHint = state.data.outcomeCode === 'CREDENTIAL_MISSING' ? '无凭证。' : ''
    const parts = [credentialHint, state.message, state.data.detail].filter(Boolean)
    return parts.join(' ') || '自检未通过，未获得可用结果。'
  }
  if (isMockMode(connector)) {
    return '模拟模式：不代表真实 API 连通，不能作为联调或生产证据。'
  }
  return '本次只读调用已返回；仍需结合 A1–A8 证据等级判断是否已接通。'
}

const selfTestResultClass = (connector: ConnectorCapability) => {
  return selfTests.value[connector.code]?.responseOk ? 'result-success' : 'result-error'
}

const selfTestResultIcon = (connector: ConnectorCapability) => {
  return selfTests.value[connector.code]?.responseOk ? 'mdi:check-circle-outline' : 'mdi:alert-circle-outline'
}

onMounted(() => {
  refreshShop()
  void loadConnectors()
  void loadSelfDescription()
  void loadOperationCatalog()
})
</script>

<style scoped>
.connector-page { background: var(--color-background); min-height: 100dvh; }
.card-surface { background: var(--color-surface); border-radius: var(--radius-md); box-shadow: var(--shadow-sm); }
.truth-note {
  display: flex; align-items: flex-start; gap: 0.625rem; padding: 0.75rem 1rem; margin-bottom: 1rem;
  color: var(--color-warning-dark); background: var(--color-warning-light); border: 1px solid var(--color-border);
  border-radius: var(--radius-md); font-size: var(--font-size-2);
}
.truth-note :deep(svg) { flex-shrink: 0; margin-top: 0.125rem; }
.loading-card { display: flex; flex-direction: column; gap: 0.75rem; padding: 1rem; background: var(--color-surface); border-radius: var(--radius-md); }
.sk-line { height: 0.875rem; } .sk-line-sm { width: 30%; } .sk-line-lg { width: 70%; height: 1.5rem; } .sk-block { height: 12rem; }
.error-card { display: flex; align-items: flex-start; gap: 0.75rem; padding: 1rem; color: var(--color-error); background: var(--color-light-red); border-radius: var(--radius-md); }
.error-card p { margin: 0.25rem 0 0; color: var(--color-on-surface); font-size: var(--font-size-2); }
.error-card .secondary-btn { margin-left: auto; }
.connector-card { padding: 1.25rem; margin-bottom: 1rem; }
.connector-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; margin-bottom: 1rem; }
.connector-title-row { display: flex; align-items: center; flex-wrap: wrap; gap: 0.5rem; }
.connector-head h2 { margin: 0; color: var(--color-on-surface); font-size: var(--font-size-5); }
.connector-subtitle { margin: 0.25rem 0 0; color: var(--color-muted); font-size: var(--font-size-2); }
.code-chip { padding: 0.125rem 0.5rem; border-radius: var(--radius-sm); background: var(--color-muted-light); color: var(--color-muted); font-family: var(--font-mono); font-size: var(--font-size-1); }
.mode-pill { padding: 0.25rem 0.625rem; border-radius: var(--radius-full); font-size: var(--font-size-1); font-weight: 600; white-space: nowrap; }
.mode-real { color: var(--color-success); background: var(--color-success-light); }
.mode-mock { color: var(--color-warning-dark); background: var(--color-warning-light); }
.status-banner { display: flex; gap: 0.75rem; padding: 0.875rem 1rem; border-radius: var(--radius-md); margin-bottom: 1rem; }
.status-banner strong { font-size: var(--font-size-3); }
.status-banner p { margin: 0.25rem 0 0; font-size: var(--font-size-2); line-height: var(--line-height-snug); }
.status-pending { color: var(--color-warning-dark); background: var(--color-warning-light); }
.status-success { color: var(--color-success); background: var(--color-success-light); }
.status-error { color: var(--color-error); background: var(--color-light-red); }
.metrics-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.75rem; margin-bottom: 1.25rem; }
.metric { display: flex; flex-direction: column; gap: 0.25rem; padding: 0.75rem; border-radius: var(--radius-sm); background: var(--color-muted-light); }
.metric span { color: var(--color-muted); font-size: var(--font-size-1); }
.metric strong { color: var(--color-on-surface); font-size: var(--font-size-2); }
.metric small { color: var(--color-muted); font-size: var(--font-size-1); }
.section-title-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; margin-bottom: 0.75rem; }
.section-title-row h3 { margin: 0; color: var(--color-on-surface); font-size: var(--font-size-3); }
.selfcheck-card { padding: 1rem; margin-bottom: 1rem; }
.section-sub { margin: 0.25rem 0 0; color: var(--color-muted); font-size: var(--font-size-1); line-height: 1.5; }
.catalog-head { margin-top: 1.25rem; }
.kv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(12rem, 1fr)); gap: 0.5rem; }
.kv { display: flex; flex-direction: column; gap: 0.125rem; background: var(--color-surface-variant); border-radius: var(--radius-sm); padding: 0.5rem 0.625rem; }
.k { font-size: 0.75rem; color: var(--color-muted); }
.v { font-size: 0.875rem; color: var(--color-on-surface); }
.flag-mock { color: var(--color-warning-dark); }
.flag-real { color: var(--color-success); }
.mock-warning { margin: 0.75rem 0 0; padding: 0.5rem 0.75rem; border-radius: var(--radius-sm); background: var(--color-warning-light); color: var(--color-warning-dark); font-size: 0.8125rem; }
.inline-error { display: flex; align-items: center; gap: 0.5rem; padding: 0.5rem 0.75rem; border-radius: var(--radius-sm); background: var(--color-light-red); color: var(--color-error); font-size: 0.8125rem; }
.inline-empty { padding: 0.5rem 0.75rem; color: var(--color-muted); font-size: 0.8125rem; }
.link-btn { background: none; border: none; color: var(--color-primary); cursor: pointer; font-size: 0.8125rem; padding: 0.25rem 0; }
.path-cell, .params-cell { max-width: 18rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.section-title-row p { margin: 0.25rem 0 0; }
.muted { color: var(--color-muted); font-size: var(--font-size-1); }
.criteria-block, .operations-block, .self-test-block { padding-top: 1rem; border-top: 1px solid var(--color-border); margin-top: 1rem; }
.criteria-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0.5rem; }
.criterion { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; padding: 0.625rem 0.75rem; border-radius: var(--radius-sm); background: var(--color-muted-light); }
.criterion span { color: var(--color-muted); font-family: var(--font-mono); font-size: var(--font-size-1); }
.criterion strong { color: var(--color-on-surface); font-size: var(--font-size-2); }
.blocker-summary { margin: 0.75rem 0 0; color: var(--color-warning-dark); font-size: var(--font-size-2); }
.table-wrap { overflow-x: auto; }
.connector-table { min-width: 760px; }
.path-cell { max-width: 22rem; overflow-wrap: anywhere; }
.tag-implemented { color: var(--color-success); background: var(--color-success-light); }
.tag-missing { color: var(--color-warning-dark); background: var(--color-warning-light); }
.primary-btn, .secondary-btn { padding: 0.5rem 1rem; border-radius: var(--radius-sm); cursor: pointer; font-size: var(--font-size-2); }
.primary-btn { border: none; color: var(--color-on-primary); background: var(--color-primary); }
.secondary-btn { border: 1px solid var(--color-border); color: var(--color-on-surface); background: var(--color-surface); }
.primary-btn:disabled, .secondary-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.inline-warning { margin: 0 0 0.75rem; color: var(--color-warning-dark); font-size: var(--font-size-2); }
.self-test-result { padding: 0.875rem 1rem; border-radius: var(--radius-md); }
.result-success { color: var(--color-success); background: var(--color-success-light); }
.result-error { color: var(--color-error); background: var(--color-light-red); }
.result-head { display: flex; align-items: center; gap: 0.5rem; }
.result-head .code-chip { margin-left: auto; }
.self-test-result p { margin: 0.5rem 0; color: var(--color-on-surface); font-size: var(--font-size-2); line-height: var(--line-height-snug); }
.result-meta { display: flex; flex-wrap: wrap; gap: 0.5rem 1rem; color: var(--color-muted); font-family: var(--font-mono); font-size: var(--font-size-1); }
.preflight-block { padding-top: 1rem; border-top: 1px solid var(--color-border); margin-top: 1rem; }
.preflight-actions { display: flex; align-items: center; justify-content: flex-end; gap: 0.75rem; flex-wrap: wrap; }
.preflight-option { display: inline-flex; align-items: center; gap: 0.375rem; color: var(--color-muted); font-size: var(--font-size-1); white-space: nowrap; }
.preflight-result { padding: 0.875rem 1rem; border-radius: var(--radius-md); }
.result-pending { color: var(--color-warning-dark); background: var(--color-warning-light); }
.preflight-stages { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.625rem; margin-top: 0.875rem; }
.preflight-stage { padding: 0.75rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); }
.stage-head { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.stage-name { color: var(--color-on-surface); font-family: var(--font-mono); font-size: var(--font-size-1); font-weight: 700; overflow-wrap: anywhere; }
.stage-status { padding: 0.125rem 0.375rem; border-radius: var(--radius-sm); font-family: var(--font-mono); font-size: var(--font-size-1); font-weight: 700; white-space: nowrap; }
.stage-pass .stage-status { color: var(--color-success); background: var(--color-success-light); }
.stage-fail .stage-status { color: var(--color-error); background: var(--color-light-red); }
.stage-skip .stage-status { color: var(--color-warning-dark); background: var(--color-warning-light); }
.stage-detail { margin: 0.5rem 0 0; color: var(--color-on-surface); font-size: var(--font-size-1); line-height: var(--line-height-snug); overflow-wrap: anywhere; }
.stage-meta { display: grid; gap: 0.25rem; margin: 0.5rem 0 0; }
.stage-meta div { display: flex; justify-content: space-between; gap: 0.5rem; }
.stage-meta dt, .stage-meta dd { margin: 0; font-family: var(--font-mono); font-size: var(--font-size-1); }
.stage-meta dt { color: var(--color-muted); }
.stage-meta dd { color: var(--color-on-surface); overflow-wrap: anywhere; text-align: right; }
.stage-remediation { margin: 0.5rem 0 0; color: var(--color-warning-dark); font-size: var(--font-size-1); line-height: var(--line-height-snug); }
.preflight-next-action { padding-top: 0.625rem; border-top: 1px solid var(--color-border); font-weight: 600; }
.credential-block { padding-top: 1rem; border-top: 1px solid var(--color-border); margin-top: 1rem; }
.credential-state-pill { padding: 0.25rem 0.625rem; border-radius: var(--radius-full); font-size: var(--font-size-1); font-weight: 600; white-space: nowrap; }
.credential-state-ok { color: var(--color-success); background: var(--color-success-light); }
.credential-state-missing { color: var(--color-warning-dark); background: var(--color-warning-light); }
.credential-flags { display: flex; flex-wrap: wrap; gap: 0.375rem 0.625rem; margin-bottom: 0.75rem; }
.credential-flags span { padding: 0.25rem 0.5rem; border-radius: var(--radius-sm); background: var(--color-muted-light); color: var(--color-muted); font-family: var(--font-mono); font-size: var(--font-size-1); }
.credential-flags .flag-ok { color: var(--color-success); }
.credential-flags .flag-missing { color: var(--color-warning-dark); }
.credential-message { margin: 0 0 0.75rem; padding: 0.625rem 0.75rem; border-radius: var(--radius-sm); font-size: var(--font-size-2); }
.credential-message.message-ok { color: var(--color-success); background: var(--color-success-light); }
.credential-message.message-error { color: var(--color-error); background: var(--color-light-red); }
.credential-form { display: grid; gap: 0.75rem; }
.credential-field { display: grid; gap: 0.375rem; }
.credential-field span { color: var(--color-muted); font-size: var(--font-size-1); }
.credential-field input { width: 100%; padding: 0.5rem 0.625rem; border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-surface); color: var(--color-on-surface); font-family: var(--font-mono); font-size: var(--font-size-2); }
.credential-clear { display: inline-flex; align-items: center; gap: 0.375rem; color: var(--color-warning-dark); font-size: var(--font-size-2); }
.credential-actions { display: flex; align-items: center; justify-content: flex-end; gap: 0.75rem; flex-wrap: wrap; }
.credential-helper { flex: 1 1 24rem; margin: 0; color: var(--color-muted); font-size: var(--font-size-1); line-height: var(--line-height-snug); }
.danger-btn { color: var(--color-error); border-color: var(--color-error); }@media (min-width: 768px) { .credential-form { grid-template-columns: repeat(2, minmax(0, 1fr)); } .credential-clear, .credential-actions { grid-column: 1 / -1; } }
@media (max-width: 1024px) { .metrics-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .criteria-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 768px) { .main-content { margin-left: 0; padding: 1rem; } .connector-card { padding: 1rem; } .connector-head, .section-title-row { flex-direction: column; } .metrics-grid, .criteria-grid, .preflight-stages { grid-template-columns: 1fr; } .preflight-actions { justify-content: flex-start; } }
</style>
