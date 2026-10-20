<template>
  <Teleport to="body">
    <Transition name="toast">
      <div v-if="toastVisible" class="toast-container" :class="toastType">
        <div class="toast-icon">
          <span v-if="toastType === 'success'">✓</span>
          <span v-else-if="toastType === 'error'">✕</span>
          <span v-else>ℹ</span>
        </div>
        <span class="toast-message">{{ toastMessage }}</span>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { useToast } from '../composables/useToast'

const { toastVisible, toastMessage, toastType } = useToast()
</script>

<style scoped>
.toast-container {
  position: fixed;
  top: 80px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
  z-index: 9999;
  font-size: 0.875rem;
  font-weight: 500;
  min-width: 160px;
  max-width: 320px;
  font-family: inherit;
}

.toast-container.success {
  background: var(--color-primary-light);
  border: 1px solid var(--color-primary);
  color: var(--color-primary);
}

.toast-container.error {
  background: var(--color-light-red);
  border: 1px solid var(--color-error);
  color: var(--color-error);
}

.toast-container.info {
  background: var(--color-primary-light);
  border: 1px solid var(--color-primary);
  color: var(--color-primary);
}

.toast-icon {
  font-size: 1rem;
  font-weight: bold;
  flex-shrink: 0;
  color: currentColor;
}

.toast-message {
  flex: 1;
  word-break: break-word;
}

.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}

.toast-enter-from {
  opacity: 0;
  transform: translateX(-50%) translateY(-20px);
}

.toast-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-20px);
}
</style>
