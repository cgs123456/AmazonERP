import request from './auth'
import type { ApiResponse } from './types'

/**
 * 分时调价接口层（amz-service-ad，`/ad/bidSchedule*`）。
 *
 * 这一组和其它广告配置不一样：**它会被真实执行**。
 * `BidScheduleExecutor` 每小时整点扫一次启用规则，按「基准价 × 倍率」把新竞价
 * 下发到 Advertising API（基准价首次触达时认领并持久化，所以不会逐小时连乘）。
 * 因此页面上任何写操作都必须二次确认，并把影响说清楚。
 *
 * 三条影响判断的后端事实：
 * 1. 命中条件只有小时区间 `start_hour <= 当前小时 <= end_hour`，**不支持跨零点**，
 *    22→2 这种写法永远不会生效，所以后端在写入时就拒；晚间要覆盖到凌晨得拆成两条。
 * 2. 同一小时命中多条启用规则时，按查询返回顺序依次应用，同一个关键词最终留下的是
 *    最后一条的倍率（都是相对基准价算的，不会叠乘，但结果取决于顺序）。
 * 3. 倍率允许区间是业务判断（0.10~5.00，取自 V1 预置的 0.7/1.2/1.5 留余量）；
 *    执行器另外兜绝对价 0.02~1000，但那只是兜底，不是给人当旋钮用的。
 */

export interface BidSchedule {
  id?: number
  shopId?: number | string
  /** 留空表示该店铺全部活动 */
  campaignId?: string | null
  startHour: number | string
  endHour: number | string
  multiplier: number | string
  enabled?: number | null
  createTime?: string
}

export const MIN_MULTIPLIER = 0.10
export const MAX_MULTIPLIER = 5.00
/** 与 V1 迁移预置规则同形的三个常用倍率 */
export const MULTIPLIER_PRESETS = [0.7, 1.0, 1.2, 1.5]

export const listBidSchedules = (shopId: number | string, q: { size?: number; cursor?: string | null } = {}) =>
  request.get<void, ApiResponse<BidSchedule[]>>(`/ad/bidSchedule/${shopId}`, { params: q })

export const createBidSchedule = (schedule: BidSchedule) =>
  request.post<void, ApiResponse<BidSchedule>>('/ad/bidSchedule', schedule)

/** PUT 可以只带被改字段，后端按「库里现值 + 本次非空覆盖」校验合并形态 */
export const updateBidSchedule = (id: number, patch: Partial<BidSchedule>) =>
  request.put<void, ApiResponse<BidSchedule>>(`/ad/bidSchedule/${id}`, patch)

export const toggleBidSchedule = (id: number, enabled: boolean) =>
  request.post<void, ApiResponse<boolean>>(
    `/ad/bidSchedule/${id}/toggle`,
    undefined,
    { params: { enabled } }
  )

export const deleteBidSchedule = (id: number) =>
  request.delete<void, ApiResponse<boolean>>(`/ad/bidSchedule/${id}`)

/** 小时区间 → 可读文本；endHour 是含的，所以右端写成下一个整点 */
export const hourRangeText = (start?: number | string | null, end?: number | string | null): string => {
  if (start === null || start === undefined || end === null || end === undefined) return '未填'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(Number(start))}:00-${pad(Number(end) + 1)}:00`
}
