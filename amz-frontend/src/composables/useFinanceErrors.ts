import { ref } from 'vue'

/**
 * 财务页的错误条状态。刻意做成**模块级单例**，照 `useToast.ts` 的既有写法
 * （module-level ref，无 provide/inject、无 Pinia）。
 *
 * **为什么是单例而不是每个 tab 各自持有一份**：Finance.vue 顶部的错误区
 * （`v-if="errors.length"`）只渲染一次，但 8 个 tab 都在往里写；
 * 更关键的是 `loadCursor` 依赖「按 `标签：` 前缀过滤」这条约定——
 * 并发的 loader 各自只清自己那条，不会擦掉别人的横幅。把 `errors` 按 tab
 * 拆开，页级错误区会消失，这条约定也就没有载体了。
 *
 * **搬移时不要顺手改成分组件持有**：那会让「某个 tab 的请求失败了但顶部没提示」
 * 变成一类新 bug，且没有测试会红（错误区在父组件，失败的 loader 在子组件）。
 */

/** 页级错误条。Finance.vue 顶部渲染一次，所有 tab 共用。 */
const errors = ref<string[]>([])

/** 错误明细兜底：后端可能给 string，也可能是异常对象。 */
const pushFinanceError = (tag: string, detail: unknown) => {
  const msg = detail instanceof Error ? detail.message : (detail as string | undefined) || '后端返回异常'
  if (!errors.value.some((x) => x.startsWith(`${tag}：`))) errors.value.push(`${tag}：${msg}`)
}

const pushError = (text: string) => {
  if (!errors.value.includes(text)) errors.value.push(text)
}

/**
 * 只清某个 loader 自己那条错误（按 `name：` 前缀）。
 *
 * 并发 loader 必须走这里而不是整体清空：回款与结算两个列表会同时拉，
 * 整体清空会让先失败的那个横幅被后成功的那个抹掉。
 */
const clearErrorsFor = (name: string) => {
  errors.value = errors.value.filter((x) => !x.startsWith(`${name}：`))
}

export function useFinanceErrors() {
  return { errors, pushError, pushFinanceError, clearErrorsFor }
}
