import { describe, it, expect } from 'vitest'
import { renderPackingSlipHtml, escapeHtml, openPackingSlip, type PackingSlipData } from '../utils/packingSlip'

/**
 * 本地发货单打印契约（批 3 P1）。
 * <p>
 * 边界：这不是 Amazon 面单（Shipping API 属独立凭证体系，本仓库无其模型），
 * 而是随包裹附的拣货/发货单。本测试钉住三个「印错就没人复核」的点：
 * 数量合计、PII/标题的 HTML 转义、空值占位。
 */
describe('发货单打印', () => {
  const base: PackingSlipData = {
    orderNo: '114-0000000-7599439',
    shopName: '美国站主店',
    buyerName: 'John Smith',
    carrierCode: 'USPS',
    carrierName: 'US Postal Service',
    trackingNumber: '9400111899223197428490',
    shipServiceLevel: 'Standard',
    fulfillmentChannel: 'MFN',
    purchaseDate: '2026-10-10 12:30',
    lines: [
      { sku: 'SYN-ELE-0101', title: '无线耳机', quantity: 2, asin: 'B0KMDRQTX7' },
      { sku: 'SYN-ELE-0102', title: '蓝牙音箱', quantity: 1, asin: 'B0ABCDEFGH' }
    ]
  }

  it('渲染订单号、店铺、承运商与运单号', () => {
    const html = renderPackingSlipHtml(base)
    expect(html).toContain('114-0000000-7599439')
    expect(html).toContain('美国站主店')
    expect(html).toContain('US Postal Service')
    expect(html).toContain('9400111899223197428490')
    expect(html).toContain('SYN-ELE-0101')
    expect(html).toContain('B0KMDRQTX7')
  })

  it('数量合计等于各行之和', () => {
    const html = renderPackingSlipHtml(base)
    // 合计行应出现 3（2 + 1）
    expect(html).toMatch(/合计[\s\S]*?class="num">3</)
  })

  it('转义买家名与商品标题中的 HTML（防注入，来自平台数据）', () => {
    const html = renderPackingSlipHtml({
      ...base,
      buyerName: '<script>alert(1)</script>',
      lines: [{ sku: 'S1', title: 'A & B <b>x</b>', quantity: 1 }]
    })
    expect(html).not.toContain('<script>alert(1)</script>')
    expect(html).toContain('&lt;script&gt;')
    expect(html).toContain('A &amp; B &lt;b&gt;x&lt;/b&gt;')
  })

  it('空字段显示「—」而不是留白（留白会被当成漏印）', () => {
    const html = renderPackingSlipHtml({
      orderNo: 'X-1',
      shopName: 'S',
      lines: []
    })
    expect(html).toContain('—')
    expect(html).toContain('无明细行')
  })

  it('carrierName 缺失时退回展示 carrierCode', () => {
    const html = renderPackingSlipHtml({ ...base, carrierName: undefined })
    expect(html).toContain('USPS')
  })

  it('弹窗被拦截时 openPackingSlip 返回 false（调用方据此提示，不静默）', () => {
    const originalOpen = window.open
    window.open = (() => null) as typeof window.open
    try {
      expect(openPackingSlip(base)).toBe(false)
    } finally {
      window.open = originalOpen
    }
  })

  it('escapeHtml 覆盖五个字符', () => {
    expect(escapeHtml(`&<>"'`)).toBe('&amp;&lt;&gt;&quot;&#39;')
    expect(escapeHtml(null)).toBe('')
  })
})
