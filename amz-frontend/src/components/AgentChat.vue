<template>
  <Teleport to="body">
    <Transition name="chat-fade">
      <div v-if="visible" class="agent-chat-window">
        <div class="chat-header">
          <div class="chat-title">
            <Icon icon="mdi:robot" width="20" color="#4f46e5" />
            <span>运营助手</span>
          </div>
          <div class="chat-status">
            <span class="status-dot"></span>
            <span class="status-text">DeepSeek V3</span>
          </div>
          <button class="close-btn" @click="$emit('update:visible', false)">
            <Icon icon="mdi:close" width="20" />
          </button>
        </div>

        <div class="chat-messages" ref="messagesContainer">
          <div
            v-for="(msg, i) in messages"
            :key="i"
            :class="['message', msg.role]"
          >
            <div class="message-content">{{ msg.content }}</div>
          </div>
          <div v-if="loading" class="message assistant">
            <div class="message-content typing">正在思考...</div>
          </div>
        </div>

        <div class="chat-input-area">
          <input
            v-model="inputText"
            type="text"
            placeholder="输入运营问题，如：最近7天销量如何？"
            @keyup.enter="sendMessage"
            class="chat-input"
          />
          <button class="send-btn" @click="sendMessage" :disabled="loading">
            <Icon icon="mdi:send" width="20" />
          </button>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import { Icon } from '@iconify/vue'

defineProps<{ visible: boolean }>()
defineEmits<{
  'update:visible': [boolean]
}>()

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
}

const inputText = ref('')
const loading = ref(false)
const messagesContainer = ref<HTMLElement | null>(null)

const messages = ref<ChatMessage[]>([
  {
    role: 'assistant',
    content: '您好！我是 Amazon ERP 运营助手。可以帮您查询订单、库存、广告、利润等数据，也可以提供运营建议。请问有什么可以帮您？'
  }
])

const sendMessage = async () => {
  const text = inputText.value.trim()
  if (!text || loading.value) return

  messages.value.push({ role: 'user', content: text })
  inputText.value = ''
  loading.value = true
  await scrollToBottom()

  // 模拟 Agent 回复（生产环境应调用后端 /agent/memory/chat 接口）
  setTimeout(async () => {
    loading.value = false
    const reply = generateMockReply(text)
    messages.value.push({ role: 'assistant', content: reply })
    await scrollToBottom()
  }, 1200)
}

const generateMockReply = (question: string): string => {
  if (question.includes('订单') || question.includes('销量')) {
    return '近 7 天共产生订单 162 单，销售额 $8,456.80。其中 Shop A (US) 占 45%，环比增长 12%。最畅销 SKU 为 B08X4-001（无线蓝牙耳机）。'
  }
  if (question.includes('库存')) {
    return '当前有 3 个 SKU 库存预警：B08X4-001（可售 4 天）、B08X4-004（可售 6 天）、B08X4-006（可售 14 天）。建议尽快通过 1688 补货。'
  }
  if (question.includes('广告') || question.includes('ACoS')) {
    return '当前整体 ACoS 为 24.9%（健康区间）。活动"关键词-蓝牙耳机-US"表现最优（ACoS 20.6%），"品牌广告-Shop B"ACoS 偏高（42.1%），建议降低 bid 或暂停。'
  }
  if (question.includes('利润')) {
    return '本月毛利率 41.9%，毛利润 $5,222.60。其中 Shop A (US) 利润贡献最高（$1,060），Shop D (JP) 出现亏损（-$413.84），建议检查该店铺成本结构。'
  }
  return '收到您的问题。我可以帮您查询订单、库存、广告、利润等运营数据，请尝试输入具体问题，如"最近7天销量如何？"或"哪些SKU需要补货？"'
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
  }
}
</script>

<style scoped>
.agent-chat-window {
  position: fixed;
  bottom: 24px;
  right: 24px;
  width: 400px;
  height: 560px;
  background: white;
  border-radius: 16px;
  box-shadow: 0 12px 48px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  z-index: 9999;
  overflow: hidden;
}

.chat-header {
  padding: 16px 20px;
  background: linear-gradient(135deg, #4f46e5 0%, #6366f1 100%);
  color: white;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.chat-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
}

.chat-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  opacity: 0.9;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
}

.close-btn {
  background: none;
  border: none;
  color: white;
  cursor: pointer;
  padding: 4px;
  border-radius: 6px;
  transition: background 0.2s;
}

.close-btn:hover { background: rgba(255, 255, 255, 0.2); }

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.message { display: flex; }
.message.user { justify-content: flex-end; }
.message.assistant { justify-content: flex-start; }

.message-content {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.5;
}

.message.user .message-content {
  background: #4f46e5;
  color: white;
  border-bottom-right-radius: 4px;
}

.message.assistant .message-content {
  background: #f3f4f6;
  color: #1f2937;
  border-bottom-left-radius: 4px;
}

.message-content.typing { color: #9ca3af; font-style: italic; }

.chat-input-area {
  padding: 12px 16px;
  border-top: 1px solid #e5e7eb;
  display: flex;
  gap: 8px;
}

.chat-input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #e0e0e0;
  border-radius: 10px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.chat-input:focus { border-color: #4f46e5; }

.send-btn {
  width: 40px;
  height: 40px;
  background: #4f46e5;
  color: white;
  border: none;
  border-radius: 10px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s;
}

.send-btn:hover { background: #4338ca; }
.send-btn:disabled { background: #c7d2fe; cursor: not-allowed; }

.chat-fade-enter-active, .chat-fade-leave-active { transition: all 0.3s ease; }
.chat-fade-enter-from, .chat-fade-leave-to { opacity: 0; transform: translateY(20px); }
</style>
