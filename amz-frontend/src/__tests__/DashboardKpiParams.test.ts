import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * Dashboard KPI 请求的空店铺处理。
 *
 * <p><b>为什么钉这一条</b>（§S33.50）：`Dashboard.vue` 直接传
 * {@code getKpiData(getCurrentShopId())}，而 `getCurrentShopId()` 在未选中店铺时
 * <b>按设计返回空串</b>（`utils/shop.ts`：取不到就回退空串，让调用方提示选店）。
 * 同一文件里另两个请求都写了 `|| undefined`，只有这一处没有。
 *
 * <p>不处理的后果不是「查不到数据」：axios 会把 {@code shopId: ''} 序列化成
 * {@code ?shopId=}，后端 `@RequestParam Long shopId` 绑到空串后转 Long 失败，
 * 抛 {@code MethodArgumentTypeMismatchException} → 500，而不是控制器自己那句
 * {@code Result.failure("shopId must not be null")}。页面上看到的是一次服务端错误，
 * 运营无法从中看出「该先选店铺」。
 *
 * <p>口径来源：{@code utils/query.ts} 的 `params()` 就是为这件事写的
 * （「后端把空串 @RequestParam 绑成 ''，等于把「未知」当答案提交」），
 * 这里只是让 KPI 这一个口子也用上它。
 */
const { requestGetMock } = vi.hoisted(() => ({ requestGetMock: vi.fn() }))

vi.mock('../api/auth', () => ({
  default: { get: requestGetMock }
}))

// 真实引入，不打桩：要验的正是 params() 有没有把空串剔掉
import { getKpiData } from '../api/dashboard'
import { params } from '../utils/query'

describe('getKpiData 的查询参数构造', () => {
  beforeEach(() => {
    requestGetMock.mockReset()
    requestGetMock.mockResolvedValue({ code: 200, data: [] })
  })

  it('选中店铺时把 shopId 带进 query', async () => {
    await getKpiData(7)

    expect(requestGetMock).toHaveBeenCalledTimes(1)
    const [, config] = requestGetMock.mock.calls[0]
    expect(config.params).toEqual({ shopId: 7 })
  })

  it('未选中店铺（空串）时整个 shopId 键都不出现，不发 ?shopId=', async () => {
    await getKpiData('')

    const [, config] = requestGetMock.mock.calls[0]
    expect(config.params).toEqual({})
    // 关键不是「值是空」而是「键不存在」：axpx 只有键存在才会序列化成 shopId=
    expect('shopId' in config.params).toBe(false)
  })

  it('字符串数字同样透传（店铺 id 在 localStorage 里是字符串）', async () => {
    await getKpiData('12')

    const [, config] = requestGetMock.mock.calls[0]
    expect(config.params).toEqual({ shopId: '12' })
  })

  it('params() 本身的口径：undefined / null / 空串一律不进 query', () => {
    expect(params({ a: 1, b: undefined, c: null, d: '', e: 0, f: false }))
      .toEqual({ a: 1, e: 0, f: false })
  })
})
