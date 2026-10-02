/**
 * 展示层数值归一。
 *
 * 后端 MyBatis 的 SUM/AVG/DECIMAL/BIGINT 经 Jackson 序列化后可能是字符串
 * （"12" / "98.50"），直接参与比较或 toFixed 会静默得到 NaN。
 * 收口成一个函数，避免每个 api 模块各写一份、各漏一种情况。
 */
export const asNumber = (value: unknown, fallback = 0): number => {
  if (value === null || value === undefined || value === '') return fallback
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}
