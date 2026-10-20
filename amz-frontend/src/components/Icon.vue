<script setup lang="ts">
/**
 * 统一图标组件（Lucide 本地化）。
 *
 * 为什么替换 @iconify/vue：Iconify 的 <Icon icon="mdi:xxx"> 在未预装图标集时
 * 会去 api.iconify.design 取数据——跨境 ERP 常部署在内网/离线环境，届时图标
 * 全部变成空白方块，且首屏多出一条外部请求。Lucide 随包走，tree-shaking 后
 * 只打进这里显式引用的图标。
 *
 * 组件名刻意保留为 `Icon`（而非 `LucideIcon`）：20 个组件按名字导入它，
 * 单测/端到端用 `stubs: { Icon: true }` 按组件名打桩——改名会让桩静默失效，
 * 测试仍绿但断言对象变了。模板里的 mdi 图标名也一并保留，语义映射收在这一处，
 * 调用方（含 ConnectorCenter 这类按状态算图标名的）完全不用改。
 *
 * MDI 名 → Lucide 名的映射不求形似，求语义：例如 mdi:amazon 是品牌 icon 而
 * Lucide 不做品牌，取 ShoppingBag；mdi:wechat / mdi:sina-weibo 同理。
 * 同一个 Lucide 图标可以被多个 MDI 名复用（如所有 cart-* 都是 ShoppingCart），
 * 这比硬造 102 个“一一对应”更诚实。
 *
 * 未登记的图标名走 CircleHelp 兜底并 console.warn：静默回退成空图标会让
 * “新增图标却忘了登记”在生产才暴露，日志至少能在联调时看见。
 */
import { computed, warn } from 'vue'
import type { Component } from 'vue'
import {
  ArrowLeftRight,
  BadgeCheck,
  Banknote,
  Bell,
  BellOff,
  BellRing,
  BookOpen,
  BookOpenCheck,
  BookOpenText,
  Bot,
  Brain,
  Cable,
  Calculator,
  CalendarClock,
  CalendarX,
  ChartColumn,
  ChartLine,
  Check,
  ChevronRight,
  CircleAlert,
  CircleCheck,
  CircleDashed,
  CircleDollarSign,
  CircleHelp,
  CircleUserRound,
  ClipboardCheck,
  ClipboardX,
  Clock,
  ClockAlert,
  ClockArrowUp,
  Compass,
  DatabaseSearch,
  DatabaseZap,
  Eye,
  FileChartColumn,
  FileText,
  FileUp,
  FileX,
  Headset,
  House,
  Hourglass,
  Images,
  Info,
  Landmark,
  LayoutDashboard,
  ListOrdered,
  LogOut,
  MailCheck,
  MessageCircle,
  MessageCircleMore,
  MessageSquareX,
  Minus,
  OctagonAlert,
  Package,
  PackageMinus,
  PackageX,
  Paperclip,
  QrCode,
  RefreshCw,
  RefreshCwOff,
  Scale,
  Search,
  Send,
  Settings,
  Share2,
  ShieldAlert,
  ShieldCheck,
  ShipWheel,
  ShoppingBag,
  ShoppingCart,
  Smartphone,
  StarOff,
  Store,
  Tag,
  Target,
  TrendingDown,
  TrendingUp,
  TriangleAlert,
  Truck,
  UserCog,
  UserLock,
  Wallet,
  Warehouse,
  WifiOff,
  X,
  ZoomIn
} from '@lucide/vue'

/** MDI 图标名 → Lucide 组件。键是历史遗留的 mdi 名，值必须真实存在于 @lucide/vue。 */
const ICONS: Record<string, Component> = {
  'mdi:account-circle-outline': CircleUserRound,
  'mdi:account-clock-outline': ClockArrowUp,
  'mdi:account-cog-outline': UserCog,
  'mdi:account-lock-outline': UserLock,
  'mdi:alert': TriangleAlert,
  'mdi:alert-circle-outline': CircleAlert,
  'mdi:alert-octagon-outline': OctagonAlert,
  'mdi:amazon': ShoppingBag,
  'mdi:bank-outline': Landmark,
  'mdi:bell-alert-outline': BellRing,
  'mdi:bell-off-outline': BellOff,
  'mdi:bell-outline': Bell,
  'mdi:book-open-outline': BookOpen,
  'mdi:book-open-page-variant': BookOpenCheck,
  'mdi:book-open-page-variant-outline': BookOpenText,
  'mdi:brain': Brain,
  'mdi:calculator-variant-outline': Calculator,
  'mdi:calendar-clock': CalendarClock,
  'mdi:calendar-remove-outline': CalendarX,
  'mdi:cart': ShoppingCart,
  'mdi:cart-arrow-right': ShoppingCart,
  'mdi:cart-outline': ShoppingCart,
  'mdi:cart-plus': ShoppingCart,
  'mdi:cash-multiple': Banknote,
  'mdi:cellphone': Smartphone,
  'mdi:chart-box-outline': ChartColumn,
  'mdi:chart-line': ChartLine,
  'mdi:chat-remove-outline': MessageSquareX,
  'mdi:check': Check,
  'mdi:check-circle-outline': CircleCheck,
  'mdi:check-decagram-outline': BadgeCheck,
  'mdi:chevron-right': ChevronRight,
  'mdi:clipboard-check': ClipboardCheck,
  'mdi:clipboard-check-outline': ClipboardCheck,
  'mdi:clipboard-text-off-outline': ClipboardX,
  'mdi:clock-alert-outline': ClockAlert,
  'mdi:clock-outline': Clock,
  'mdi:close': X,
  'mdi:cog-outline': Settings,
  'mdi:compass-off-outline': Compass,
  'mdi:compass-outline': Compass,
  'mdi:connection': Cable,
  'mdi:currency-usd': CircleDollarSign,
  'mdi:currency-usd-off': CircleDollarSign,
  'mdi:database-off-outline': DatabaseZap,
  'mdi:database-search-outline': DatabaseSearch,
  'mdi:email-check-outline': MailCheck,
  'mdi:eye-outline': Eye,
  'mdi:file-chart-outline': FileChartColumn,
  'mdi:file-document-outline': FileText,
  'mdi:file-document-remove-outline': FileX,
  'mdi:file-remove-outline': FileX,
  'mdi:file-upload-outline': FileUp,
  'mdi:finance': Wallet,
  'mdi:headset-outline': Headset,
  'mdi:home': House,
  'mdi:image-multiple-outline': Images,
  'mdi:information-outline': Info,
  'mdi:logout': LogOut,
  'mdi:magnify': Search,
  'mdi:magnify-plus-outline': ZoomIn,
  'mdi:minus': Minus,
  'mdi:package-variant-closed': Package,
  'mdi:package-variant-closed-remove': PackageX,
  'mdi:package-variant-minus': PackageMinus,
  'mdi:package-variant-remove': PackageX,
  'mdi:paperclip': Paperclip,
  'mdi:progress-alert': CircleAlert,
  'mdi:progress-clock': CircleDashed,
  'mdi:qqchat': MessageCircle,
  'mdi:qrcode': QrCode,
  'mdi:queue-right': ListOrdered,
  'mdi:refresh': RefreshCw,
  'mdi:robot': Bot,
  'mdi:scale-balance': Scale,
  'mdi:send': Send,
  'mdi:shield-alert-outline': ShieldAlert,
  'mdi:shield-check-outline': ShieldCheck,
  'mdi:ship-wheel': ShipWheel,
  'mdi:sina-weibo': Share2,
  'mdi:star-off-outline': StarOff,
  'mdi:store-alert-outline': Store,
  'mdi:store-cog-outline': Store,
  'mdi:store-outline': Store,
  'mdi:storefront-outline': Store,
  'mdi:swap-horizontal': ArrowLeftRight,
  'mdi:swap-horizontal-bold': ArrowLeftRight,
  'mdi:sync': RefreshCw,
  'mdi:sync-off': RefreshCwOff,
  'mdi:tag-text-outline': Tag,
  'mdi:target-variant': Target,
  'mdi:timer-sand': Hourglass,
  'mdi:trending-down': TrendingDown,
  'mdi:trending-up': TrendingUp,
  'mdi:truck-alert-outline': Truck,
  'mdi:truck-fast-outline': Truck,
  'mdi:truck-in': Truck,
  'mdi:truck-out': Truck,
  'mdi:view-dashboard': LayoutDashboard,
  'mdi:warehouse': Warehouse,
  'mdi:wechat': MessageCircleMore,
  'mdi:wifi-off': WifiOff
}

const props = defineProps<{
  /** 历史 mdi 图标名；未登记时回退 CircleHelp */
  icon: string
  /** 同 @iconify/vue 的 width/height（可为数字或带单位的字符串） */
  width?: string | number
  height?: string | number
}>()

const resolved = computed(() => {
  const component = ICONS[props.icon]
  if (!component) {
    warn(`[Icon] 未登记的图标名 "${props.icon}"，已回退为 CircleHelp`)
    return CircleHelp
  }
  return component
})
</script>

<template>
  <component
    :is="resolved"
    :size="width ?? height ?? 24"
    aria-hidden="true"
    focusable="false"
  />
</template>
