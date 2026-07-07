import { onUnmounted, ref } from 'vue'
import { websocketManager } from '@/utils/websocket'

/**
 * WebSocket 组合式函数
 * 注意：WebSocket 连接已在 main.ts 中自动初始化
 */
export function useWebSocket() {
    const isConnected = ref(false)
    const unsubscribeHandlers: Array<() => void> = []

    // 发送消息
    const send = (data: string | object) => {
        websocketManager.send(data)
    }

    // 监听消息
    const onMessage = (handler: (data: unknown) => void) => {
        const unsubscribe = websocketManager.onMessage(handler)
        unsubscribeHandlers.push(unsubscribe)
    }

    // 更新连接状态 - 事件驱动
    const updateConnectionStatus = () => {
        isConnected.value = websocketManager.isConnected()
    }

    // 监听连接状态变化
    const statusUnsubscribe = websocketManager.onStatusChange(updateConnectionStatus)
    unsubscribeHandlers.push(statusUnsubscribe)

    // 初始化状态
    updateConnectionStatus()

    onUnmounted(() => {
        unsubscribeHandlers.forEach(unsub => unsub())
    })

    return {
        isConnected,
        send,
        onMessage
    }
}
