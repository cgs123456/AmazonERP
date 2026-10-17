import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * 利润趋势请求的 wire 参数（§S33.81）。
 *
 * <p><b>为什么单独钉 wrapper 层</b>：ReportCenter.test.ts 把 `rpt.profitTrend` 整体
 * mock 掉了，它验的是「视图 → wrapper」的调用约定（传的是不是 7），
 * 从不看 wrapper 往 HTTP 层发的查询参数名。把 `days` 改回 `hours: days`，
 * 视图测试 18 项全绿——但后端 `@RequestParam(defaultValue = "7") Integer days`
 * 收不到 days，静默用默认值，用户填的 30 被吞掉，趋势图照样出数，
 * 只是出的永远是 7 天的数。参数名是跨层契约，只在调用方钉不住它。
 *
 * <p><b>背景</b>：后端快照按天存（stat_time 记当天 00:00，明细表没有小时列），
 * 旧接口参数是 hours，24 小时窗口在按天存的快照上只命中今天一条，
 * 趋势图退化成一个点。现在参数统一为 days。
 */
const { requestGetMock } = vi.hoisted(() => ({ requestGetMock: vi.fn() }))

vi.mock('../api/auth', () => ({
  default: { get: requestGetMock }
}))

// 真实引入：要验的正是 wrapper 拼出来的 query 对象
import { profitTrend } from '../api/report'

describe('profitTrend 的查询参数构造', () => {
  beforeEach(() => {
    requestGetMock.mockReset()
    requestGetMock.mockResolvedValue({ code: 200, data: {} })
  })

  it('days 以 days 为参数名发出——后端读的是 days，改名即静默失效', async () => {
    await profitTrend(7, 'SKU-1', 30)

    expect(requestGetMock).toHaveBeenCalledTimes(1)
    const [url, config] = requestGetMock.mock.calls[0]
    expect(url).toBe('/report/profit/trend/7')
    expect(config.params).toEqual({ sku: 'SKU-1', days: 30 })
    // 钉住「没有 hours 残留」：mutation 把它改回 hours: days 时，
    // toEqual 会失败，但这里的显式断言让失败原因一眼可读。
    expect('hours' in config.params).toBe(false)
  })

  it('days 省略时不发送该键（后端 defaultValue=7 兜底）', async () => {
    await profitTrend(7, 'SKU-1')

    const [, config] = requestGetMock.mock.calls[0]
    expect(config.params).toEqual({ sku: 'SKU-1' })
    expect('days' in config.params).toBe(false)
  })
})
