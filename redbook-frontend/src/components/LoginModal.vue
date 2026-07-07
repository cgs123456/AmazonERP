<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="modal-overlay" @click="handleOverlayClick">
        <div class="modal-container" @click.stop>
          <button class="close-btn" @click="closeModal">
            <Icon icon="mdi:close" width="20" color="#666" />
          </button>

          <div class="modal-content">
            <!-- Logo -->
            <div class="modal-logo">
              <Icon icon="mdi:book-open-page-variant" width="48" color="#ff2442" />
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
                  <Icon icon="mdi:qrcode" width="120" color="#333" />
                </div>
              </div>
              <p class="qrcode-tip">
                <Icon icon="mdi:cellphone" width="16" />
                打开
                <span class="highlight">小红书App</span>
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
                  <Icon icon="mdi:wechat" width="36" color="#07c160" />
                </button>
                <button class="social-btn" title="QQ登录">
                  <Icon icon="mdi:qqchat" width="36" color="#12b7f5" />
                </button>
                <button class="social-btn" title="微博登录">
                  <Icon icon="mdi:sina-weibo" width="36" color="#e6162d" />
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
import { ref } from 'vue'
import { Icon } from '@iconify/vue'
import { sendVerifyCode, verifyLogin } from '../api/auth'
import { useToast } from '../composables/useToast'

const { showToast } = useToast()

interface Props {
  visible: boolean
}

interface Emits {
  (e: 'update:visible', value: boolean): void
  (e: 'login-success'): void
}

defineProps<Props>()
const emit = defineEmits<Emits>()

const activeTab = ref<'qrcode' | 'phone'>('qrcode')
const phoneNumber = ref('')
const verifyCode = ref('')
const agreed = ref(false)
const countdown = ref(0)
const loading = ref(false)

let timer: number | null = null

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
      // 登录成功，保存 token
      const token = response.data
      localStorage.setItem('token', token)
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
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 20px;
}

.modal-container {
  position: relative;
  background: white;
  border-radius: 24px;
  width: 100%;
  max-width: 420px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  animation: slideUp 0.3s ease-out;
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
  top: 16px;
  right: 16px;
  width: 36px;
  height: 36px;
  border: none;
  background: #f5f5f5;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s;
  z-index: 10;
  padding: 0;
}

.close-btn:hover {
  background: #e5e5e5;
}

.modal-content {
  padding: 48px 40px 40px;
}

.modal-logo {
  display: flex;
  justify-content: center;
  margin-bottom: 20px;
}

.modal-title {
  font-size: 20px;
  font-weight: 600;
  color: #333;
  text-align: center;
  margin-bottom: 32px;
}

.tab-container {
  display: flex;
  gap: 8px;
  margin-bottom: 32px;
  background: #f5f5f5;
  border-radius: 12px;
  padding: 4px;
}

.tab-item {
  flex: 1;
  padding: 10px;
  border: none;
  background: transparent;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 500;
  color: #666;
  cursor: pointer;
  transition: all 0.3s;
}

.tab-item.active {
  background: white;
  color: #ff2442;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

/* 二维码登录 */
.qrcode-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.qrcode-box {
  width: 200px;
  height: 200px;
  background: #f5f5f5;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
  border: 2px solid #e5e5e5;
}

.qrcode-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
}

.qrcode-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #666;
  margin-bottom: 8px;
}

.highlight {
  color: #ff2442;
  font-weight: 600;
}

.qrcode-subtitle {
  font-size: 12px;
  color: #999;
}

/* 手机号登录 */
.phone-section {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.input-group {
  width: 100%;
}

.input-wrapper {
  display: flex;
  align-items: center;
  background: #f7f7f7;
  border-radius: 12px;
  padding: 14px 16px;
  border: 2px solid transparent;
  transition: all 0.3s;
}

.input-wrapper:focus-within {
  background: white;
  border-color: #ff2442;
}

.country-code {
  font-size: 14px;
  color: #333;
  margin-right: 12px;
  padding-right: 12px;
  border-right: 1px solid #e5e5e5;
}

.phone-input,
.code-input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 14px;
  color: #333;
}

.phone-input::placeholder,
.code-input::placeholder {
  color: #999;
}

.send-code-btn {
  padding: 6px 16px;
  background: transparent;
  border: none;
  color: #ff2442;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: opacity 0.2s;
}

.send-code-btn:hover:not(:disabled) {
  opacity: 0.8;
}

.send-code-btn:disabled {
  color: #999;
  cursor: not-allowed;
}

.login-btn {
  width: 100%;
  padding: 14px;
  background: linear-gradient(135deg, #ff2442 0%, #ff4d6d 100%);
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.3s;
  box-shadow: 0 4px 12px rgba(255, 36, 66, 0.3);
}

.login-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(255, 36, 66, 0.4);
}

.login-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
  transform: none;
}

.agreement {
  margin-top: 8px;
}

.checkbox-wrapper {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  cursor: pointer;
}

.checkbox-wrapper input[type='checkbox'] {
  margin-top: 2px;
  cursor: pointer;
}

.checkbox-text {
  font-size: 12px;
  color: #999;
  line-height: 1.5;
}

.link {
  color: #ff2442;
  text-decoration: none;
}

.link:hover {
  text-decoration: underline;
}

/* 其他登录方式 */
.other-login {
  margin-top: 32px;
}

.divider {
  position: relative;
  text-align: center;
  margin-bottom: 20px;
}

.divider::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 100%;
  height: 1px;
  background: #e5e5e5;
}

.divider span {
  position: relative;
  display: inline-block;
  padding: 0 16px;
  background: white;
  font-size: 12px;
  color: #999;
}

.social-login {
  display: flex;
  justify-content: center;
  gap: 28px;
}

.social-btn {
  width: 56px;
  height: 56px;
  border: 1px solid #e5e5e5;
  background: white;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.3s;
  padding: 0;
}

.social-btn:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 16px rgba(0, 0, 0, 0.12);
  border-color: #d5d5d5;
}

/* 过渡动画 */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

/* 响应式 */
@media (max-width: 480px) {
  .modal-content {
    padding: 40px 24px 32px;
  }

  .modal-title {
    font-size: 18px;
  }

  .qrcode-box {
    width: 180px;
    height: 180px;
  }
}
</style>
