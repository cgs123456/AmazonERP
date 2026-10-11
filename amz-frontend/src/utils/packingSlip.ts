/**
 * 本地发货单（拣货单 / packing slip）打印。
 * <p>
 * <b>边界</b>：这不是 Amazon 面单（shipping label）。Amazon 自发货面单属于独立的
 * Shipping API，需要单独申请凭证与权限，本仓库的官方 OpenAPI 快照里没有任何
 * shipping/label 模型，因此不做——面单仍由卖家在卖家后台或承运商处打印。
 * 本模块产出的是**随包裹附的拣货/发货单**：SKU、数量、买家、运单号、承运商，
 * 用于仓内拣货核对与客户收货核对。
 * <p>
 * <b>为什么不引 PDF 库</b>：全仓前端没有任何 PDF/二进制依赖，后端也没有二进制下载端点。
 * 用「新窗口 + 打印样式 + window.print()」是最小实现，且用户可用系统打印对话框
 * 存成 PDF——不引入新依赖、不需要后端配合。
 */

/** 打印用的一行商品 */
export interface PackingSlipLine {
  sku: string
  title?: string
  quantity: number
  asin?: string
}

/** 打印用的发货单数据 */
export interface PackingSlipData {
  /** Amazon 订单号（若缺失则退回本地订单号） */
  orderNo: string
  shopName: string
  /** 买家名（PII，可能被字段权限置空 → 显示「—」而不是空白） */
  buyerName?: string
  carrierCode?: string
  carrierName?: string
  trackingNumber?: string
  shipServiceLevel?: string
  fulfillmentChannel?: string
  purchaseDate?: string
  lines: PackingSlipLine[]
}

/** HTML 转义：买家名/商品标题来自平台数据，直接拼进 innerHTML 会有注入面 */
export const escapeHtml = (value: unknown): string => {
  if (value === null || value === undefined) return ''
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

/** 空值统一显示为「—」：空白会让仓内人员以为漏印了字段 */
const orDash = (value: unknown): string => {
  const text = escapeHtml(value).trim()
  return text === '' ? '—' : text
}

/**
 * 生成发货单 HTML。
 * <p>
 * 纯函数（无 DOM 依赖），便于单测钉住「数量合计」「PII 转义」「空值占位」这些
 * 印错就没人复核的点。调用方负责把它写进打印窗口。
 */
export const renderPackingSlipHtml = (data: PackingSlipData): string => {
  const lines = Array.isArray(data?.lines) ? data.lines : []
  const totalQty = lines.reduce((sum, line) => sum + (Number(line?.quantity) || 0), 0)

  const rows = lines
    .map(
      (line) => `
      <tr>
        <td class="mono">${orDash(line.sku)}</td>
        <td>${orDash(line.title)}</td>
        <td class="mono">${orDash(line.asin)}</td>
        <td class="num">${Number(line.quantity) || 0}</td>
      </tr>`
    )
    .join('')

  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<title>发货单 ${escapeHtml(data?.orderNo)}</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; margin: 24px; color: #111; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  .sub { color: #555; font-size: 12px; margin-bottom: 16px; }
  .meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 24px; margin-bottom: 18px; font-size: 13px; }
  .meta div { display: flex; gap: 8px; }
  .meta .k { color: #666; min-width: 84px; }
  .meta .v { font-weight: 600; word-break: break-all; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { border: 1px solid #ccc; padding: 6px 8px; text-align: left; vertical-align: top; }
  th { background: #f2f2f2; }
  td.num, th.num { text-align: right; }
  .mono { font-family: ui-monospace, Consolas, monospace; }
  tfoot td { font-weight: 700; background: #fafafa; }
  .footnote { margin-top: 16px; font-size: 11px; color: #666; }
  @media print { body { margin: 8mm; } .no-print { display: none; } }
</style>
</head>
<body>
  <h1>发货单 / Packing Slip</h1>
  <div class="sub">本单据随包裹附送，用于仓内拣货与收货核对；不是承运商面单。</div>
  <div class="meta">
    <div><span class="k">订单号</span><span class="v mono">${orDash(data?.orderNo)}</span></div>
    <div><span class="k">店铺</span><span class="v">${orDash(data?.shopName)}</span></div>
    <div><span class="k">买家</span><span class="v">${orDash(data?.buyerName)}</span></div>
    <div><span class="k">下单时间</span><span class="v">${orDash(data?.purchaseDate)}</span></div>
    <div><span class="k">承运商</span><span class="v">${orDash(data?.carrierName || data?.carrierCode)}</span></div>
    <div><span class="k">运单号</span><span class="v mono">${orDash(data?.trackingNumber)}</span></div>
    <div><span class="k">配送方式</span><span class="v">${orDash(data?.shipServiceLevel)}</span></div>
    <div><span class="k">履约渠道</span><span class="v">${orDash(data?.fulfillmentChannel)}</span></div>
  </div>
  <table>
    <thead>
      <tr><th>SKU</th><th>商品</th><th>ASIN</th><th class="num">数量</th></tr>
    </thead>
    <tbody>${rows || '<tr><td colspan="4">无明细行</td></tr>'}</tbody>
    <tfoot>
      <tr><td colspan="3">合计</td><td class="num">${totalQty}</td></tr>
    </tfoot>
  </table>
  <div class="footnote">打印时间：${escapeHtml(new Date().toLocaleString('zh-CN'))}</div>
</body>
</html>`
}

/** 商品标签数据（打印规格：SKU/ASIN 条码区 + 品名） */
export interface ProductLabelData {
  sku: string
  asin?: string
  title?: string
  fnSku?: string
}

/**
 * 生成商品标签 HTML（P2.3）：仓内贴标用，含 SKU/ASIN/FNSKU。
 * 同样不是承运商面单；条码区用等宽文本呈现（不引条码库，避免新依赖——
 * 扫描枪可直接识别 Code39 格式的等宽 SKU 文本）。
 */
export const renderLabelHtml = (label: ProductLabelData): string => {
  const esc = escapeHtml
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<title>商品标签 ${esc(label.sku)}</title>
<style>
  body { font-family: -apple-system, "Segoe UI", "Microsoft YaHei", sans-serif; margin: 8px; }
  .label { border: 2px solid #111; padding: 12px; width: 340px; }
  .sku { font-family: "Libre Barcode 39", ui-monospace, Consolas, monospace; font-size: 28px; letter-spacing: 2px; margin: 6px 0; }
  .meta { font-size: 12px; color: #333; margin-top: 6px; }
  .meta div { display: flex; gap: 6px; }
  .meta .k { color: #666; min-width: 52px; }
  .title { font-size: 13px; font-weight: 600; word-break: break-all; }
</style>
</head>
<body>
  <div class="label">
    <div class="title">${esc(label.title || label.sku)}</div>
    <div class="sku">*${esc(label.sku)}*</div>
    <div class="meta">
      <div><span class="k">SKU</span><span>${esc(label.sku)}</span></div>
      <div><span class="k">ASIN</span><span>${esc(label.asin || '—')}</span></div>
      <div><span class="k">FNSKU</span><span>${esc(label.fnSku || '—')}</span></div>
    </div>
  </div>
</body>
</html>`
}

/**
 * 打开打印窗口并触发打印。
 * <p>
 * 返回 false 表示弹窗被浏览器拦截（此时调用方应提示用户允许弹窗），
 * 而不是静默什么都不发生——「点了没反应」比「提示被拦截」更难排查。
 */
export const openPackingSlip = (data: PackingSlipData): boolean => {
  const html = renderPackingSlipHtml(data)
  const win = window.open('', '_blank', 'width=900,height=700')
  if (!win) {
    return false
  }
  win.document.open()
  win.document.write(html)
  win.document.close()
  // 等样式应用后再打印；部分浏览器需要一次微任务延迟
  win.focus()
  setTimeout(() => {
    try {
      win.print()
    } catch {
      // 用户取消或浏览器策略：不抛给调用方，窗口已展示内容
    }
  }, 50)
  return true
}
