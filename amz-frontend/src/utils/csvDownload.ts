import request from '../api/auth'

/**
 * CSV 导出下载（P1.5）。
 * <p>
 * 导出端点要求 token/shopId 请求头，window.open 带不上——必须走 axios blob
 * 再用 object URL 触发浏览器下载。响应拦截器对 blob 场景直接返回 Blob 本体
 * （_hiddenFields 掩码对 Blob 是 no-op），文件名由调用方给定（导出端点的
 * 命名模式固定，无需解析 Content-Disposition）。
 */
export const downloadCsv = async (
  url: string,
  params: Record<string, unknown>,
  filename: string
): Promise<void> => {
  const blob = await request.get<void, Blob>(url, {
    params,
    responseType: 'blob'
  })
  if (!(blob instanceof Blob)) {
    // 后端返回 JSON 错误体（如业务失败）时 axios 仍按文本进 Blob——
    // 这里不做内容嗅探，直接按失败处理提示重试
    throw new Error('导出失败：响应不是 CSV 文件')
  }
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(link.href)
}
