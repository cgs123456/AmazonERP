/**
 * Query 参数清洗：undefined / null / 空串不进 query 串。
 *
 * 为什么必须收敛成一份（历史：9 个 api 文件曾有 10 份逐字节相同的本地实现）：
 * - 后端把空串 @RequestParam 绑成 ''，等于把「未知」当答案提交（Spring 语义）；
 * - 复制体没有任何行为差异，但每次修正口径（例如要不要过滤空白串）都要改 10 处，
 *   漏一处就是两套语义并存的静默漂移。
 *
 * 使用：`import { params } from '@/utils/query'`，然后 `{ params: params({ a, b }) }`。
 */
export const params = (q: Record<string, unknown>): Record<string, unknown> => {
  const out: Record<string, unknown> = {}
  Object.entries(q).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') out[k] = v
  })
  return out
}
