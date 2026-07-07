<template>
  <Teleport to="body">
    <Transition name="assistant">
      <div v-if="visible" class="assistant-overlay" @click="handleClose">
        <!-- 聊天窗口 -->
        <div class="chat-window" @click.stop>
          <!-- 头部 -->
          <div class="chat-header">
            <div class="header-left">
              <Icon icon="mdi:robot" width="24" class="robot-icon" />
              <div class="header-info">
                <h3>购物助手</h3>
                <span class="status">在线</span>
              </div>
            </div>
          </div>

          <!-- 消息列表 -->
          <div class="chat-messages" ref="messagesContainer">
            <div 
              v-for="(message, index) in messages" 
              :key="index"
              class="message-item"
              :class="{ 'user-message': message.type === 'user', 'assistant-message': message.type === 'assistant' }"
            >
              <div class="message-avatar">
                <Icon v-if="message.type === 'assistant'" icon="mdi:robot" width="24" />
                <Icon v-else icon="mdi:account-circle" width="24" />
              </div>
              <div class="message-content">
                <div class="message-text">{{ message.text }}</div>
                <div class="message-time">{{ message.time }}</div>
              </div>
            </div>

            <!-- 加载中 -->
            <div v-if="loading" class="message-item assistant-message">
              <div class="message-avatar">
                <Icon icon="mdi:robot" width="24" />
              </div>
              <div class="message-content">
                <div class="message-text typing">
                  <span></span>
                  <span></span>
                  <span></span>
                </div>
              </div>
            </div>
          </div>

          <!-- 输入框 -->
          <div class="chat-input">
            <input 
              v-model="inputText"
              type="text" 
              placeholder="输入您的问题..."
              @keyup.enter="sendMessage"
              :disabled="loading"
            />
            <button 
              class="send-btn" 
              @click="sendMessage"
              :disabled="!inputText.trim() || loading"
            >
              发送
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import { Icon } from '@iconify/vue'
import { chatWithAgent } from '../api/product'

interface Message {
  type: 'user' | 'assistant'
  text: string
  time: string
}

interface Props {
  visible: boolean
}

interface Emits {
  (e: 'update:visible', value: boolean): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const messages = ref<Message[]>([
  {
    type: 'assistant',
    text: '您好！我是购物助手，有什么可以帮您的吗？',
    time: getCurrentTime()
  }
])
const inputText = ref('')
const loading = ref(false)
const messagesContainer = ref<HTMLElement | null>(null)

// 监听弹窗打开
watch(() => props.visible, (newVisible) => {
  if (newVisible) {
    nextTick(() => {
      scrollToBottom()
    })
  }
})

// 获取当前时间
function getCurrentTime() {
  const now = new Date()
  return `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`
}

// 滚动到底部
function scrollToBottom() {
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}

// 发送消息
async function sendMessage() {
  if (!inputText.value.trim() || loading.value) return

  const userMessage: Message = {
    type: 'user',
    text: inputText.value,
    time: getCurrentTime()
  }

  messages.value.push(userMessage)
  const question = inputText.value
  inputText.value = ''

  nextTick(() => {
    scrollToBottom()
  })

  // 调用真实的 AI 聊天接口
  loading.value = true
  
  try {
    const response = await chatWithAgent(question)
    const replyText = response.code === 200 && response.data 
      ? response.data 
      : '抱歉，我暂时无法回答这个问题。'
    
    messages.value.push({
      type: 'assistant',
      text: replyText,
      time: getCurrentTime()
    })
  } catch (error) {
    messages.value.push({
      type: 'assistant',
      text: '抱歉，服务暂时不可用，请稍后再试。',
      time: getCurrentTime()
    })
  } finally {
    loading.value = false
    nextTick(() => {
      scrollToBottom()
    })
  }
}

// 关闭弹窗
function handleClose() {
  emit('update:visible', false)
}
</script>

<style scoped>
.assistant-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.3);
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  padding: 24px;
  z-index: 9998;
}

.chat-window {
  width: 380px;
  height: 600px;
  background: white;
  border-radius: 16px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 头部 */
.chat-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 20px;
  background: linear-gradient(135deg, #ff2442 0%, #ff4757 100%);
  color: white;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.robot-icon {
  animation: float 3s ease-in-out infinite;
}

@keyframes float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-5px); }
}

.header-info h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.status {
  font-size: 12px;
  opacity: 0.9;
}

/* 消息列表 */
.chat-messages {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
  background: #f8f9fa;
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.message-avatar {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #e9ecef;
  color: #666;
}

.assistant-message .message-avatar {
  background: linear-gradient(135deg, #ff2442 0%, #ff4757 100%);
  color: white;
}

.user-message {
  flex-direction: row-reverse;
}

.user-message .message-avatar {
  background: #007bff;
  color: white;
}

.message-content {
  flex: 1;
  max-width: 70%;
}

.user-message .message-content {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.message-text {
  padding: 12px 16px;
  border-radius: 12px;
  background: white;
  color: #333;
  line-height: 1.5;
  font-size: 14px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}

.user-message .message-text {
  background: linear-gradient(135deg, #007bff 0%, #0056b3 100%);
  color: white;
}

.message-time {
  margin-top: 6px;
  font-size: 11px;
  color: #999;
}

/* 输入中动画 */
.typing {
  display: flex;
  gap: 4px;
  padding: 12px 16px;
}

.typing span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #999;
  animation: typing 1.4s infinite;
}

.typing span:nth-child(2) {
  animation-delay: 0.2s;
}

.typing span:nth-child(3) {
  animation-delay: 0.4s;
}

@keyframes typing {
  0%, 60%, 100% {
    transform: translateY(0);
    opacity: 0.7;
  }
  30% {
    transform: translateY(-10px);
    opacity: 1;
  }
}

/* 输入框 */
.chat-input {
  display: flex;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #e9ecef;
  background: white;
}

.chat-input input {
  flex: 1;
  padding: 10px 16px;
  border: 1px solid #ddd;
  border-radius: 20px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.chat-input input:focus {
  border-color: #ff2442;
}

.send-btn {
  padding: 0 20px;
  height: 40px;
  border: none;
  background: linear-gradient(135deg, #ff2442 0%, #ff4757 100%);
  color: white;
  border-radius: 20px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s;
  flex-shrink: 0;
  font-size: 14px;
  font-weight: 500;
}

.send-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(255, 36, 66, 0.3);
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 动画 */
.assistant-enter-active,
.assistant-leave-active {
  transition: all 0.3s;
}

.assistant-enter-from,
.assistant-leave-to {
  opacity: 0;
}

.assistant-enter-from .chat-window,
.assistant-leave-to .chat-window {
  transform: translateY(20px) scale(0.95);
}

/* 响应式 */
@media (max-width: 600px) {
  .assistant-overlay {
    padding: 0;
    align-items: stretch;
    justify-content: stretch;
  }

  .chat-window {
    width: 100%;
    height: 100%;
    border-radius: 0;
  }
}
</style>
