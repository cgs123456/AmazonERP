import { ref } from 'vue'

/**
 * 降级 mock 标识（B4：与 AdManager / ProfitReport 的 mock-badge 同模式）。
 * <p>
 * 规则：初始为 mock 态（展示降级数据时必须挂「示例数据」徽）；
 * 任何 200 真实响应（含零数据空态）后 markLive() 摘徽；
 * 非 200 / 异常保持 mock 态。徽标样式为全局 .mock-badge（见 style.css）。
 */
export function useMockFlag() {
  const isMock = ref(true)

  const markLive = () => {
    isMock.value = false
  }

  const markMock = () => {
    isMock.value = true
  }

  return { isMock, markLive, markMock }
}
