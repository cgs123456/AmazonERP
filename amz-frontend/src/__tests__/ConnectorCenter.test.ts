import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import ConnectorCenter from '../views/ConnectorCenter.vue'

vi.mock('@/api/connectors', () => ({
  listConnectors: vi.fn(),
  selfTestConnector: vi.fn(),
  preflightConnector: vi.fn(),
  getConnectorCredentialStatus: vi.fn(),
  saveConnectorCredential: vi.fn(),
  deleteConnectorCredential: vi.fn()
}))

import {
  listConnectors,
  selfTestConnector,
  preflightConnector,
  getConnectorCredentialStatus,
  saveConnectorCredential,
  deleteConnectorCredential,
  type ConnectorCapability,
  type ConnectorPreflightReport,
  type ConnectorCredentialStatus
} from '@/api/connectors'

const mockedList = vi.mocked(listConnectors)
const mockedSelfTest = vi.mocked(selfTestConnector)
const mockedPreflight = vi.mocked(preflightConnector)
const mockedCredentialStatus = vi.mocked(getConnectorCredentialStatus)
const mockedSaveCredential = vi.mocked(saveConnectorCredential)
const mockedDeleteCredential = vi.mocked(deleteConnectorCredential)

const capability = (overrides: Partial<ConnectorCapability> = {}): ConnectorCapability => ({
  code: 'spapi',
  name: 'Amazon Selling Partner API',
  enabled: true,
  profile: 'prod',
  mockActive: false,
  credentialSource: 'none',
  credentialCount: 0,
  implementedCount: 12,
  notImplementedCount: 7,
  operations: [
    { id: 'orders.getOrders', path: '/orders/v0/orders', status: 'IMPLEMENTED', note: 'OrdersClient.fetchOrders' },
    { id: 'notifications', path: '(未实现)', status: 'NOT_IMPLEMENTED', note: 'spec §1.4.1' }
  ],
  evidenceLevel: 'E3',
  apiReady: false,
  reachable: false,
  displayText: '具备对接能力（未联调）',
  blockerSummary: 'A1(E2<E4), A5(E0<E4)',
  criteria: { A1: 'E2', A5: 'E0' },
  lastCallAt: null,
  lastResult: 'NEVER_RUN',
  lastOutcomeCode: 'NOT_RUN',
  ...overrides
})

const preflightReport = (overrides: Partial<ConnectorPreflightReport> = {}): ConnectorPreflightReport => ({
  shopId: 7,
  marketplaceId: 'ATVPDKIKX0DER',
  region: 'NA',
  host: 'sellingpartnerapi-na.amazon.com',
  endpointOverridden: false,
  checkedAt: '2026-09-27T00:00:00Z',
  ready: false,
  stages: [
    {
      name: 'CREDENTIAL',
      status: 'PASS',
      durationMs: 1,
      errorCode: null,
      platformStatus: null,
      remediation: null,
      detail: 'credential fields complete'
    },
    {
      name: 'LWA_TOKEN',
      status: 'FAIL',
      durationMs: 30,
      errorCode: 'AUTH_FAILED',
      platformStatus: 400,
      remediation: 'refresh_token 已失效，请卖家重新授权',
      detail: 'LWA status=400'
    },
    {
      name: 'READ_API',
      status: 'SKIP',
      durationMs: 0,
      errorCode: null,
      platformStatus: null,
      remediation: 'LWA 阶段未通过，未执行只读 API 探测',
      detail: ''
    }
  ],
  nextAction: 'refresh_token 已失效，请卖家重新授权',
  ...overrides
})

const credentialStatus = (overrides: Partial<ConnectorCredentialStatus> = {}): ConnectorCredentialStatus => ({
  configured: false,
  clientIdConfigured: false,
  clientSecretConfigured: false,
  refreshTokenConfigured: false,
  awsKeysConfigured: false,
  region: null,
  marketplaceId: null,
  sellerId: null,
  updatedAt: null,
  ...overrides
})

const mountView = () => mount(ConnectorCenter, {
  global: {
    stubs: {
      AppHeader: { template: '<div />' },
      AppSidebar: { template: '<div />' },
      Icon: true
    }
  }
})

describe('连接器状态中心', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.unstubAllGlobals()
    localStorage.clear()
    localStorage.setItem('current_shop_id', '7')
    mockedCredentialStatus.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: credentialStatus()
    })
  })

  it('展示真实客户端模式、凭证缺失、未实现能力与证据边界', async () => {
    mockedList.mockResolvedValue({ code: 200, message: 'ok', data: [capability()] })

    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('Amazon Selling Partner API')
    expect(text).toContain('真实客户端模式')
    expect(text).toContain('未配置')
    expect(text).toContain('具备对接能力（未联调）')
    expect(text).toContain('12 已实现')
    expect(text).toContain('7 未实现')
    expect(text).toContain('E3 契约锁定')
    expect(text).not.toContain('已接通（联调中）')
    expect(text).not.toContain('API-Ready（已联调）')
  })

  it('后端证据冲突时不得把 E3/未联通错误显示成已接通', async () => {
    mockedList.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [capability({ displayText: '已接通（联调中）', reachable: false })]
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('状态异常：证据等级与联通声明冲突')
    expect(wrapper.text()).not.toContain('已接通（联调中）')
  })

  it('只读自检必须带当前 shopId，模拟模式结果不得被描述为真实联调成功', async () => {
    mockedList.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: [capability({ profile: 'mock', mockActive: true })]
    })
    mockedSelfTest.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: {
        connector: 'spapi',
        operation: 'orders.getOrders',
        ok: true,
        outcomeCode: '200',
        detail: 'ok orders=0 window=PT24H',
        elapsedMs: 12,
        itemCount: 0,
        at: '2026-09-26T12:00:00Z',
        evidenceLevel: 'E3',
        displayText: '具备对接能力（未联调）'
      }
    })

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find((item) => item.text().includes('运行只读自检'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    expect(mockedSelfTest).toHaveBeenCalledWith('spapi', 7)
    expect(wrapper.text()).toContain('离线自检完成')
    expect(wrapper.text()).toContain('不代表真实 API 连通')
    expect(wrapper.text()).not.toContain('真实 API 自检成功')
  })

  it('自检返回非 200 时显示后端原因，不显示成功', async () => {
    mockedList.mockResolvedValue({ code: 200, message: 'ok', data: [capability()] })
    mockedSelfTest.mockResolvedValue({
      code: 400,
      message: '自检未执行：店铺 shopId=7 无凭证',
      data: {
        connector: 'spapi',
        operation: 'orders.getOrders',
        ok: false,
        outcomeCode: 'CREDENTIAL_MISSING',
        detail: 'no credential for shopId=7',
        elapsedMs: 0,
        itemCount: 0,
        at: '2026-09-26T12:00:00Z'
      }
    })

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find((item) => item.text().includes('运行只读自检'))
    await button!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('无凭证')
    expect(wrapper.text()).not.toContain('离线自检完成')
    expect(wrapper.text()).not.toContain('真实 API 自检成功')
  })

  it('分层自检展示 CREDENTIAL/LWA_TOKEN/READ_API 三态，SKIP 不得显示为通过', async () => {
    mockedList.mockResolvedValue({ code: 200, message: 'ok', data: [capability()] })
    mockedPreflight.mockResolvedValue({ code: 200, message: 'ok', data: preflightReport() })

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find((item) => item.text().includes('运行分层连通性自检'))
    expect(button).toBeTruthy()
    await button!.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(mockedPreflight).toHaveBeenCalledWith('spapi', 7, false)
    expect(text).toContain('CREDENTIAL')
    expect(text).toContain('PASS')
    expect(text).toContain('LWA_TOKEN')
    expect(text).toContain('FAIL')
    expect(text).toContain('READ_API')
    expect(text).toContain('SKIP')
    expect(text).toContain('分层自检未全部通过')
    expect(text).not.toContain('只读链路已打通')
  })

  it('非官方端点即使三段全通过也只能显示 E2 证据边界', async () => {
    mockedList.mockResolvedValue({ code: 200, message: 'ok', data: [capability()] })
    mockedPreflight.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: preflightReport({
        endpointOverridden: true,
        ready: true,
        stages: [
          { name: 'CREDENTIAL', status: 'PASS', durationMs: 1, errorCode: null, platformStatus: null, remediation: null, detail: 'ok' },
          { name: 'LWA_TOKEN', status: 'PASS', durationMs: 2, errorCode: null, platformStatus: null, remediation: null, detail: 'ok' },
          { name: 'READ_API', status: 'PASS', durationMs: 3, errorCode: null, platformStatus: null, remediation: null, detail: 'ok' }
        ],
        nextAction: '仅可继续只读联调'
      })
    })

    const wrapper = mountView()
    await flushPromises()

    const button = wrapper.findAll('button').find((item) => item.text().includes('运行分层连通性自检'))
    await button!.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('非官方端点自检通过')
    expect(text).toContain('仅 E2')
    expect(text).not.toContain('已联调完成')
  })
  it('SP-API 凭证面板只展示脱敏状态，不把密文回填到表单', async () => {
    mockedList.mockResolvedValue({ code: 200, message: 'ok', data: [capability()] })
    mockedCredentialStatus.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: credentialStatus({
        configured: true,
        clientIdConfigured: true,
        clientSecretConfigured: true,
        refreshTokenConfigured: true,
        region: 'NA',
        marketplaceId: 'ATVPDKIKX0DER',
        sellerId: 'seller-id',
        updatedAt: '2026-09-27T10:00:00'
      })
    })

    const wrapper = mountView()
    await flushPromises()

    expect(mockedCredentialStatus).toHaveBeenCalledWith(7)
    const text = wrapper.text()
    expect(text).toContain('密钥加密保存，只写不回显')
    expect(text).toContain('clientSecret 已配置')
    expect(text).toContain('refreshToken 已配置')
    expect((wrapper.get('[data-testid="credential-client-secret"]').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('[data-testid="credential-refresh-token"]').element as HTMLInputElement).value).toBe('')
  })

  it('保存凭证后清空密文输入、展示非联调边界并强制刷新后自检', async () => {
    mockedList.mockResolvedValue({ code: 200, message: 'ok', data: [capability()] })
    mockedSaveCredential.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: credentialStatus({
        configured: true,
        clientIdConfigured: true,
        clientSecretConfigured: true,
        refreshTokenConfigured: true,
        region: 'NA',
        marketplaceId: 'ATVPDKIKX0DER'
      })
    })
    mockedPreflight.mockResolvedValue({ code: 200, message: 'ok', data: preflightReport() })

    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('[data-testid="credential-client-id"]').setValue('amzn1.application-oa2-client.test')
    await wrapper.get('[data-testid="credential-client-secret"]').setValue('client-secret-value')
    await wrapper.get('[data-testid="credential-refresh-token"]').setValue('refresh-token-value')
    await wrapper.get('[data-testid="credential-region"]').setValue('NA')
    await wrapper.get('[data-testid="credential-marketplace-id"]').setValue('ATVPDKIKX0DER')
    await wrapper.get('[data-testid="credential-form"]').trigger('submit')
    await flushPromises()

    expect(mockedSaveCredential).toHaveBeenCalledWith(7, {
      clientId: 'amzn1.application-oa2-client.test',
      clientSecret: 'client-secret-value',
      refreshToken: 'refresh-token-value',
      accessKey: '',
      secretKey: '',
      region: 'NA',
      marketplaceId: 'ATVPDKIKX0DER',
      sellerId: '',
      clearAwsKeys: false
    })
    expect((wrapper.get('[data-testid="credential-client-secret"]').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('[data-testid="credential-refresh-token"]').element as HTMLInputElement).value).toBe('')
    expect(wrapper.text()).toContain('凭证已保存')
    expect(wrapper.text()).toContain('仍需通过真实 LWA/只读 API 自检')
    expect(mockedPreflight).toHaveBeenCalledWith('spapi', 7, true)
  })

  it('确认删除凭证后清空本地状态', async () => {
    mockedList.mockResolvedValue({ code: 200, message: 'ok', data: [capability()] })
    mockedCredentialStatus.mockResolvedValue({
      code: 200,
      message: 'ok',
      data: credentialStatus({
        configured: true,
        clientIdConfigured: true,
        clientSecretConfigured: true,
        refreshTokenConfigured: true,
        region: 'NA'
      })
    })
    mockedDeleteCredential.mockResolvedValue({ code: 200, message: 'ok', data: credentialStatus() })
    vi.stubGlobal('confirm', vi.fn(() => true))

    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('[data-testid="credential-delete"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    expect(mockedDeleteCredential).toHaveBeenCalledWith(7)
    expect(wrapper.text()).toContain('尚未配置')
    expect(wrapper.text()).not.toContain('clientSecret 已配置')
  })
})