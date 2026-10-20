/**
 * 类名合并工具，shadcn-vue 组件的标准入口。
 *
 * `cn` 包（github.com/shadcn-ui/cn，由 shadcn 维护）是 clsx + tailwind-merge
 * 的合并替代：后写的 Tailwind 类覆盖前面的同类工具类（`cn('px-2', 'px-4')`
 * 只留 px-4），这正是 `<Button class="...">` 能被调用方安全覆盖的原因。
 *
 * 不要换成手写拼接：模板字符串不会去重，Tailwind 的声明顺序又不保证后者胜出，
 * 组件默认样式与调用方覆盖会变成"看运气"。
 */
export { cn } from 'cn'
