import { ref } from 'vue'
import { canUseRole, FINANCE_WRITE_ROLES, ROLE_CHANGED_EVENT } from '@/utils/identity'
import { useFinanceErrors } from '@/composables/useFinanceErrors'
import type { ApiResponse } from '@/api/types'

/**
 * 财务页的读/写出口与其共享状态。**财务专用，不要泛化成 useApiCall。**
 *
 * 另有 7 个 view 各自有一份自己的 `call()`（ConnectorQueue:161、CustomerService:518、
 * MultiplatformOrders:179、OrderAudit:380、ProductSearch:152、ReportCenter:660、
 * WarehouseAlerts:295），它们**都没有角色门控**。把本模块通用化，要么把财务的
 * 门控泄漏到那些页面，要么丢掉门控——两者都是错的。所以名字限定 `useFinanceApi`。
 *
 * ## 为什么是 `read` / `call` 两个具名函数，而不是一个带布尔参数的函数
 *
 * 这是踩过的坑，不是风格偏好。第一版把门控塞进共用的 `call()` 第一行，
 * 结果 7 个后端「只有 @ShopScoped、没有 @RequireRole」的只读查询被一起挡掉
 * （成本最低的改法），第二版改成加布尔参数 `write = true`，又漏传了 4 个调用点。
 * **漏传的方向恰好是「把允许的读也拦掉」**——比「点了报错」更糟，因为 VIEWER
 * 根本看不到入口，不会知道有这些功能，也就不会来报 bug。
 *
 * 拆成两个函数后，写必须显式写 `call`、读显式写 `read`，想漏也漏不掉。
 * 内部共用 `invoke` 只是复用 try/catch，**不影响对外的两个名字**：
 * 调用方永远看不到「这是个写吗」这样的参数。
 *
 * ## 不变式（改这里之前先跑 tools/schema/endpoint_role_audit.py）
 *
 * - `call` ⇒ 该端点是 POST/PUT/DELETE **且** 后端有 `@RequireRole`
 * - `read` ⇒ 该端点是 GET **且** 后端没有 `@RequireRole`
 *
 * 守卫：`FinanceOps.test.ts` 的「只读端点不得被角色门控挡住（写才拦）」那组，
 * 以及 E2E `VIEWER 的财务只读入口仍可点（只拦写，不拦查）`。
 * 两处都做过变异验证（给 `read` 加门控 / 删 `call` 门控，都必须变红）。
 */

/**
 * 页级「有请求在飞」标志。保持页级而非每 tab 一个：模板里 `:disabled="busy"`
 * 遍布各 tab（含确认弹窗的确定键），拆开会让「A tab 在加载时不拦 B tab 的按钮」
 * 变成一类新行为，且 `runConfirm` 不属于任何 tab。
 */
const busy = ref(false)

/**
 * 财务写入口的角色门控。
 * <p>
 * 未知角色不隐藏、不拦截：硬刷新时 AppHeader 的 getInfo 可能还没回来，
 * 因「还没取到角色」就拦住操作，等于让前端状态否决后端能力（identity.ts 的口径）。
 * <p>
 * **初值在这里给，但真正的权威读数是 `installRoleListener()`。** 本 ref 是模块级
 * 单例（与 `errors` 同因：页壳与子组件必须看同一个值），而模块级意味着它**只在
 * 首次 import 时求值一次**——localStorage 里当时还没有 `user_role` 是常态
 * （AppHeader 的 getInfo 是异步的，JWT 解析也晚于模块求值）。
 * 所以必须由页壳在 onMounted 里再读一次，否则「刷新页面时是 VIEWER」这类
 * 场景会拿到过期初值。这不是测试问题，是真实行为。
 */
const financeWriteAllowed = ref(canUseRole(FINANCE_WRITE_ROLES))

export function useFinanceApi() {
  const { pushError, pushFinanceError } = useFinanceErrors()

  const syncFinanceWriteRole = () => {
    financeWriteAllowed.value = canUseRole(FINANCE_WRITE_ROLES)
  }

  const financeWriteBlocked = (label: string): boolean => {
    if (financeWriteAllowed.value) return false
    pushFinanceError(label, '需要 OPERATOR 或 ADMIN 角色')
    return true
  }

  /** 读/写共用的执行体。**只做失败上报与 busy 收尾，不含任何角色判断。** */
  const invoke = async <T>(
    label: string,
    fn: () => Promise<ApiResponse<T>>,
    apply: (v: T) => void
  ): Promise<boolean> => {
    busy.value = true
    try {
      const res = await fn()
      if (res?.code !== 200) {
        pushError(`${label}：${res?.message || '后端返回非 200'}`)
        return false
      }
      apply(res.data as T)
      return true
    } catch (e) {
      pushError(`${label}：${e instanceof Error ? e.message : '调用失败'}`)
      return false
    } finally {
      busy.value = false
    }
  }

  /**
   * 只读查询的统一出口。**刻意不加角色门控**：本页的查询端点后端普遍没有
   * `@RequireRole`，VIEWER 查得到（含 `/collection/summary`、`/claim/summary`、
   * `/claim/reconcile`、`/discrepancy/{id}`、`/profit/sku`、
   * `/vat/calculate|rate|threshold-check`），拦住它们就是前端否决后端能力。
   */
  const read = <T>(label: string, fn: () => Promise<ApiResponse<T>>, apply: (v: T) => void) =>
    invoke(label, fn, apply)

  /**
   * 财务写操作的统一出口。**只有它能改数据，也只有它做角色门控。**
   * <p>
   * 本页的写端点在后端都声明了 `@RequireRole({"OPERATOR","ADMIN"})`，
   * 此前页面上对 VIEWER 全绿可点。点了之后拿到的是 HTTP 200 + code=400
   * （Result.failure 不改状态码，所以 axios 走 resolve 不是 reject，catch 不会触发），
   * 界面只显示一句「无权限执行该操作」——用户看到的是「功能点了报错」，
   * 而不是「这个功能不归你用」。
   * <p>
   * 门控放在这里而不是逐个按钮 `v-if`：confirm() 弹窗可能在角色变更前就已打开，
   * 或者角色信息比弹窗晚到（AppHeader 的 getInfo 与页面挂载是并发的），
   * 禁用态挡不住这种时序，而这里是所有写操作的必经之路。
   */
  const call = <T>(label: string, fn: () => Promise<ApiResponse<T>>, apply: (v: T) => void) =>
    financeWriteBlocked(label) ? Promise.resolve(false) : invoke(label, fn, apply)

  /**
   * 安装角色变更监听。**只能由 Finance.vue（页壳）调用一次。**
   * 子组件不要各自装：它们比页壳先卸载时监听会留在 window 上，
   * 而 `financeWriteAllowed` 是模块级单例，没人再负责同步它。
   */
  const installRoleListener = () => {
    syncFinanceWriteRole()   // 先同步读一次：模块级初值可能早于 localStorage 写入
    window.addEventListener(ROLE_CHANGED_EVENT, syncFinanceWriteRole)
  }
  const removeRoleListener = () => {
    window.removeEventListener(ROLE_CHANGED_EVENT, syncFinanceWriteRole)
  }

  return {
    busy,
    financeWriteAllowed,
    financeWriteBlocked,
    read,
    call,
    installRoleListener,
    removeRoleListener
  }
}
