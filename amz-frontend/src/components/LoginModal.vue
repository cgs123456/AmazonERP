<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="modal-overlay" @click="handleOverlayClick">
        <div class="modal-container" @click.stop>
          <button class="close-btn" @click="closeModal">
            <Icon icon="mdi:close" width="20" />
          </button>

          <div class="modal-content">
            <!-- Logo -->
            <div class="modal-logo">
              <Icon icon="mdi:book-open-page-variant" width="48" />
            </div>

            <!-- 标题 -->
            <h2 class="modal-title">登录后使用更多功能</h2>

            <!-- 标签切换 -->
            <div class="tab-container">
              <button
                :class="['tab-item', { active: activeTab === 'qrcode' }]"
                @click="activeTab = 'qrcode'"
              >
                扫码登录
              </button>
              <button
                :class="['tab-item', { active: activeTab === 'phone' }]"
                @click="activeTab = 'phone'"
              >
                手机号登录
              </button>
            </div>

            <!-- 二维码登录 -->
            <div v-if="activeTab === 'qrcode'" class="qrcode-section">
              <div class="qrcode-box">
                <div class="qrcode-placeholder">
                  <Icon icon="mdi:qrcode" width="120" />
                </div>
              </div>
              <p class="qrcode-tip">
                <Icon icon="mdi:cellphone" width="16" />
                打开
                <span class="highlight">Amazon ERP App</span>
                扫码登录
              </p>
              <p class="qrcode-subtitle">
                扫码后请在手机上确认登录
              </p>
            </div>

            <!-- 手机号登录 -->
            <div v-else class="phone-section">
              <div class="input-group">
                <div class="input-wrapper">
                  <span class="country-code">+86</span>
                  <input
                    v-model="phoneNumber"
                    type="tel"
                    placeholder="请输入手机号"
                    maxlength="11"
                    class="phone-input"
                  />
                </div>
              </div>

              <div class="input-group">
                <div class="input-wrapper">
                  <input
                    v-model="verifyCode"
                    type="text"
                    placeholder="请输入验证码"
                    maxlength="4"
                    class="code-input"
                  />
                  <button class="send-code-btn" :disabled="countdown > 0 || loading" @click="sendCode">
                    <span v-if="loading">发送中...</span>
                    <span v-else>{{ countdown > 0 ? `${countdown}s后重试` : '获取验证码' }}</span>
                  </button>
                </div>
              </div>

              <button class="login-btn" :disabled="loading" @click="handleLogin">
                <span v-if="loading">登录中...</span>
                <span v-else>登录</span>
              </button>

              <div class="agreement">
                <label class="checkbox-wrapper">
                  <input v-model="agreed" type="checkbox" />
                  <span class="checkbox-text">
                    我已阅读并同意
                    <a href="#" class="link">《用户协议》</a>
                    和
                    <a href="#" class="link">《隐私政策》</a>
                  </span>
                </label>
              </div>
            </div>

            <!-- 其他登录方式 -->
            <div class="other-login">
              <div class="divider">
                <span>其他登录方式</span>
              </div>
              <div class="social-login">
                <button class="social-btn" title="微信登录">
                  <Icon icon="mdi:wechat" width="36" />
                </button>
                <button class="social-btn" title="QQ登录">
                  <Icon icon="mdi:qqchat" width="36" />
                </button>
                <button class="social-btn" title="微博登录">
                  <Icon icon="mdi:sina-weibo" width="36" />
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import { sendVerifyCode, verifyLogin, extractLoginToken } from '../api/auth'
import type { LoginTokenPair } from '../api/auth'
import { useToast } from '../composables/useToast'

const { showToast } = useToast()

defineProps<{ visible: boolean }>()
const emit = defineEmits<{
  'update:visible': [value: boolean]
  'login-success': []
}>()

const activeTab = ref<'qrcode' | 'phone'>('qrcode')
const phoneNumber = ref('')
const verifyCode = ref('')
const agreed = ref(false)
const countdown = ref(0)
const loading = ref(false)

let timer: number | null = null

// 组件卸载时清理倒计时，防止隐藏后仍空转
onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})

const closeModal = () => {
  emit('update:visible', false)
}

const handleOverlayClick = () => {
  closeModal()
}

const sendCode = async () => {
  if (!phoneNumber.value || phoneNumber.value.length !== 11) {
    showToast('请输入正确的手机号', 'error')
    return
  }

  if (loading.value) return

  try {
    loading.value = true
    const response = await sendVerifyCode(phoneNumber.value)

    if (response.code === 200) {
      // 安全修复：不再回显验证码，仅提示用户查看手机
      showToast('验证码已发送，请查看手机短信', 'success')

      // 开始倒计时
      countdown.value = 60
      timer = window.setInterval(() => {
        countdown.value--
        if (countdown.value <= 0 && timer) {
          clearInterval(timer)
          timer = null
        }
      }, 1000)
    } else {
      showToast(response.message || '发送验证码失败，请稍后重试', 'error')
    }
  } catch (error: unknown) {
    const errorMsg = error instanceof Error ? error.message : '发送验证码失败，请检查网络连接'
    showToast(errorMsg, 'error')
  } finally {
    loading.value = false
  }
}

const handleLogin = async () => {
  if (!agreed.value) {
    showToast('请先阅读并同意用户协议和隐私政策', 'error')
    return
  }

  if (!phoneNumber.value || phoneNumber.value.length !== 11) {
    showToast('请输入正确的手机号', 'error')
    return
  }

  if (!verifyCode.value || verifyCode.value.length !== 4) {
    showToast('请输入4位验证码', 'error')
    return
  }

  if (loading.value) return

  try {
    loading.value = true
    const response = await verifyLogin(phoneNumber.value, verifyCode.value)

    if (response.code === 200) {
      // 登录成功，保存 token（后端返回 { token, refreshToken } 对象，兼容裸字符串）
      const token = extractLoginToken(response.data)
      if (!token) {
        showToast('登录响应异常，请稍后重试', 'error')
        return
      }
      localStorage.setItem('token', token)
      if (response.data && typeof response.data === 'object' && (response.data as LoginTokenPair).refreshToken) {
        localStorage.setItem('refreshToken', (response.data as LoginTokenPair).refreshToken as string)
      }
      // 设置 token 过期时间（例如：7 天后）
      const expiryTime = Date.now() + 7 * 24 * 60 * 60 * 1000
      localStorage.setItem('token_expiry', expiryTime.toString())
      
      showToast('登录成功！', 'success')
      emit('login-success')
      closeModal()
      
      // 清空表单
      phoneNumber.value = ''
      verifyCode.value = ''
      agreed.value = false
    } else {
      showToast(response.message || '登录失败，请检查验证码是否正确', 'error')
    }
  } catch (error: unknown) {
    const errorMsg = error instanceof Error ? error.message : '登录失败，请检查网络连接'
    showToast(errorMsg, 'error')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 1rem;
}

.modal-container {
  position: relative;
  background: var(--color-surface);
  border-radius: var(--radius-xl);
  width: 100%;
  max-width: 440px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.2);
  animation: slideUp 0.3s ease-out;
  outline: none;
  -webkit-tap-highlight-color: transparent;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: translateY(30px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.close-btn {
  position: absolute;
  top: 1rem;
  right: 1rem;
  width: 32px;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--color-muted);
  cursor: pointer;
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  z-index: 10;
  padding: 0;
}

.close-btn:hover { color: var(--color-primary); }
.close-btn:focus-visible { background: var(--color-primary-light); outline: 2px solid var(--color-primary); outline-offset: 2px; }

.modal-content {
  padding: 1.5rem 1.75rem;
}

.modal-logo {
  display: flex;
  justify-content: center;
  margin-bottom: 1rem;
}

.modal-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: var(--color-on-surface);
  text-align: center;
  margin-bottom: 1.5rem;
}

.tab-container {
  display: flex;
  gap: 0.25rem;
  margin-bottom: 1.5rem;
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: 0.25rem;
}

.tab-item {
  flex: 1;
  padding: 0.5rem 0.75rem;
  border: none;
  background: transparent;
  border-radius: var(--radius-md);
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--color-muted);
  cursor: pointer;
  transition: all 0.2s;
}

.tab-item.active {
  background: var(--color-primary-light);
  color: var(--color-primary);
  box-shadow: none;
}

/* 二维码登录 */
.qrcode-section { flex: 1; }

.qrcode-box {
  width: 180px;
  height: 180px;
  background: var(--color-primary-light);
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 1rem;
  border: 2px solid var(--color-border);
}

.qrcode-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
}

.qrcode-tip {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.875rem;
  color: var(--color-muted);
  margin-bottom: 0.5rem;
}

.highlight { color: var(--color-primary); font-weight: 600; }

.qrcode-subtitle {
  font-size: 0.75rem;
  color: var(--color-muted);
  text-align: center;
}

/* 手机号登录 */
.phone-section { flex: 1; }

.input-group { width: 100%; margin-bottom: 0.75rem; }

.input-wrapper {
  display: flex;
  align-items: center;
  background: var(--color-primary-light);
  border-radius: var(--radius-md);
  padding: 0.5rem 0.75rem;
  border: 2px solid transparent;
  transition: all 0.2s;
  gap: 0.5rem;
}

.input-wrapper:focus-within {
  background: var(--color-surface);
  border-color: var(--color-primary);
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.country-code {
  font-size: 0.875rem;
  color: var(--color-muted);
  margin-right: 0.5rem;
  white-space: nowrap;
}

.phone-input,
.code-input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 0.875rem;
  color: var(--color-on-surface);
}

.phone-input::placeholder,
.code-input::placeholder {
  color: var(--color-muted);
}

.send-code-btn {
  padding: 0.375rem 0.75rem;
  background: transparent;
  border: none;
  color: var(--color-primary);
  font-size: 0.8125rem;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: opacity 0.2s;
}

.send-code-btn:hover:not(:disabled) { opacity: 0.8; }

.send-code-btn:disabled { color: var(--color-muted); cursor: not-allowed; }

.login-btn {
  width: 100%;
  padding: 0.75rem;
  background: var(--color-primary);
  color: var(--color-on-primary);
  border: none;
  border-radius: var(--radius-md);
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
}

.login-btn:hover:not(:disabled) { background: var(--color-primary-dark); }
.login-btn:disabled { opacity: 0.6; cursor: not-allowed; }

.agreement { margin-top: 0.75rem; }

.checkbox-wrapper {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  cursor: pointer;
}

.checkbox-wrapper input[type='checkbox'] {
  margin-top: 0.125rem;
  cursor: pointer;
}

.checkbox-text {
  font-size: 0.75rem;
  color: var(--color-muted);
  line-height: 1.5;
}

.link {
  color: var(--color-primary);
  text-decoration: none;
}

.link:hover { text-decoration: underline; }

/* 其他登录方式 */
.other-login { margin-top: 1.5rem; }

.divider {
  position: relative;
  text-align: center;
  margin: 1rem 0;
}

.divider::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 100%;
  height: 1px;
  background: var(--color-border);
}

.divider span {
  position: relative;
  display: inline-block;
  padding: 0 0.75rem;
  background: var(--color-surface);
  font-size: 0.75rem;
  color: var(--color-muted);
}

.social-login {
  display: flex;
  justify-content: center;
  gap: 0.75rem;
}

.social-btn {
  width: 44px;
  height: 44px;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  border-radius: var(--radius-full);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
  padding: 0;
}

.social-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  border-color: var(--color-primary);
}

.social-btn:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }

/* 过渡动画 */
.modal-enter-active,
.modal-leave-active { transition: opacity 0.3s ease; }
.modal-enter-from,
.modal-leave-to { opacity: 0; }

@media (max-width: 480px) {
  .modal-content { padding: 1.25rem 1.5rem 1.5rem; }
  .modal-title { font-size: 1.125rem; }
}
</style>
