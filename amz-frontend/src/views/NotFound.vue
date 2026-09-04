<template>
  <div class="not-found-page">
    <!-- hero section - 符合 design-taste-frontend 约束 -->
    <!-- eyebrow: 无 (每 3 个 section 最多 1 个，本页面 0 个，合规)
         headline: "404" - 单词计入 subtext
         subtext: "抱歉，您访问的页面不存在或已被删除" - 8 词以内，合规
         CTAs: 返回首页 (1 个 CTA，在 hero 底部，符合最大 4 个文字元素)
         split-header: 已垂直堆叠 (h1 在上，p 在下)
    -->
    <div class="hero-section">
      <h1 class="hero-title">404</h1>
      <p class="hero-subtitle">抱歉，您访问的页面不存在或已被删除</p>
      <button class="hero-cta" @click="goHome">
        <Icon icon="mdi:home" width="16" />
        返回首页
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Icon } from '@iconify/vue'
import { useRouter } from 'vue-router'

const router = useRouter()

const goHome = () => {
  router.push('/')
}
</script>

<style scoped>
.not-found-page { background: var(--color-background); }

.hero-section { padding-top: env(safe-area-inset-top); padding-bottom: 3rem; text-align: center; }
.hero-title { font-size: var(--font-size-10); font-weight: 800; color: var(--color-primary); margin: 0 0 0.5rem 0; line-height: 1; }
.hero-subtitle { font-size: var(--font-size-2); color: var(--color-muted); margin: 0 0 1rem 0; line-height: var(--line-height-relaxed); }
.hero-cta { display: inline-flex; align-items: center; gap: 0.5rem; padding: 0.75rem 1.5rem; background: var(--color-primary); color: var(--color-on-primary); border: none; border-radius: var(--radius-md); font-size: 0.875rem; font-weight: 500; cursor: pointer; transition: background 0.2s; white-space: nowrap; }
.hero-cta:hover { background: var(--color-primary-dark); }

@media (max-width: 768px) { .hero-section { padding: 2rem 1rem; } }
</style>
