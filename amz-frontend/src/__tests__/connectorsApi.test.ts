import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../api/auth', () => ({
  default: {
    get: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

import request from '../api/auth'
import {
  deleteConnectorCredential,
  getConnectorCredentialStatus,
  saveConnectorCredential,
  type ConnectorCredentialStatus
} from '../api/connectors'

const mockedGet = vi.mocked(request.get)
const mockedPut = vi.mocked(request.put)
const mockedDelete = vi.mocked(request.delete)
const status: ConnectorCredentialStatus = {
  configured: false,
  clientIdConfigured: false,
  clientSecretConfigured: false,
  refreshTokenConfigured: false,
  awsKeysConfigured: false,
  region: null,
  marketplaceId: null,
  sellerId: null,
  updatedAt: null
}

describe('SP-API 凭证管理 API 契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('按店铺路径读取脱敏凭证状态', async () => {
    mockedGet.mockResolvedValue({ code: 200, message: 'ok', data: status })

    await getConnectorCredentialStatus(7)

    expect(mockedGet).toHaveBeenCalledWith('/credentials/shop/7/status')
  })

  it('按店铺路径提交部分更新且不篡改敏感字段', async () => {
    const payload = {
      clientId: 'amzn1.application-oa2-client.test',
      clientSecret: 'client-secret-value',
      refreshToken: 'refresh-token-value',
      marketplaceId: 'ATVPDKIKX0DER',
      region: 'NA',
      sellerId: 'seller-id'
    }
    mockedPut.mockResolvedValue({ code: 200, message: 'ok', data: status })

    await saveConnectorCredential(7, payload)

    expect(mockedPut).toHaveBeenCalledWith('/credentials/shop/7', payload)
  })

  it('按店铺路径删除凭证', async () => {
    mockedDelete.mockResolvedValue({ code: 200, message: 'ok', data: status })

    await deleteConnectorCredential(7)

    expect(mockedDelete).toHaveBeenCalledWith('/credentials/shop/7')
  })
})