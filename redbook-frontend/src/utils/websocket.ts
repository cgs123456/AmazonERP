/**
 * WebSocket 管理类
 * 支持自动重连、心跳检测、页面刷新保持连接
 */
class WebSocketManager {
    private ws: WebSocket | null = null
    private url: string = ''
    private token: string = ''
    private reconnectTimer: number | null = null
    private heartbeatTimer: number | null = null
    private heartbeatTimeoutTimer: number | null = null
    private reconnectAttempts: number = 0
    private maxReconnectAttempts: number = 5
    private reconnectInterval: number = 3000
    private heartbeatInterval: number = 30000
    private heartbeatTimeout: number = 5000
    private isManualClose: boolean = false
    private isWaitingPong: boolean = false
    private messageHandlers: Set<(data: unknown) => void> = new Set()
    private statusHandlers: Set<(connected: boolean) => void> = new Set()

    constructor(url: string) {
        this.url = url
        this.loadToken()

        // 监听页面可见性变化
        document.addEventListener('visibilitychange', this.handleVisibilityChange.bind(this))

        // 页面卸载前保存状态
        window.addEventListener('beforeunload', this.handleBeforeUnload.bind(this))
    }

    /**
     * 从 localStorage 加载 token
     */
    private loadToken(): void {
        this.token = localStorage.getItem('token') || ''
    }

    /**
     * 连接 WebSocket
     */
    connect(): void {
        if (this.ws && this.ws.readyState === WebSocket.OPEN) {
            return
        }

        this.loadToken()

        if (!this.token) {
            return
        }

        try {
            this.ws = new WebSocket(this.url)
            this.setupEventHandlers()
        } catch (error) {
            this.reconnect()
        }
    }

    /**
     * 设置事件处理器
     */
    private setupEventHandlers(): void {
        if (!this.ws) return

        this.ws.onopen = () => {
            this.reconnectAttempts = 0

            // 发送 token 进行认证
            this.send(this.token)

            // 启动心跳
            this.startHeartbeat()

            // 通知连接状态变化
            this.notifyStatusChange(true)
        }

        this.ws.onmessage = (event) => {
            const data = event.data

            // 处理心跳响应
            if (data === 'pong') {
                this.isWaitingPong = false
                this.clearHeartbeatTimeout()
                return
            }

            // 处理认证响应
            if (data === 'auth_success') {
                return
            }

            if (data.startsWith('auth_failed')) {
                this.close()
                return
            }

            // 通知所有消息处理器
            this.messageHandlers.forEach(handler => {
                try {
                    const message = typeof data === 'string' ? JSON.parse(data) : data
                    handler(message)
                } catch (error) {
                    // 消息处理失败，静默处理
                }
            })
        }

        this.ws.onerror = (_error) => {
            // WebSocket 错误，静默处理
        }

        this.ws.onclose = (_event) => {
            this.stopHeartbeat()

            // 通知连接状态变化
            this.notifyStatusChange(false)

            // 如果不是手动关闭，则尝试重连
            if (!this.isManualClose) {
                this.reconnect()
            }
        }
    }

    /**
     * 发送消息
     */
    send(data: string | object): void {
        if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
            return
        }

        const message = typeof data === 'string' ? data : JSON.stringify(data)
        this.ws.send(message)
    }

    /**
     * 启动心跳检测
     */
    private startHeartbeat(): void {
        this.stopHeartbeat()

        this.heartbeatTimer = window.setInterval(() => {
            if (this.ws && this.ws.readyState === WebSocket.OPEN) {
                // 如果上一次心跳还没收到响应，说明连接可能有问题
                if (this.isWaitingPong) {
                    this.ws.close()
                    return
                }

                // 发送心跳
                this.isWaitingPong = true
                this.send('ping')

                // 设置超时检测
                this.heartbeatTimeoutTimer = window.setTimeout(() => {
                    if (this.isWaitingPong) {
                        if (this.ws) {
                            this.ws.close()
                        }
                    }
                }, this.heartbeatTimeout)
            }
        }, this.heartbeatInterval)
    }

    /**
     * 停止心跳检测
     */
    private stopHeartbeat(): void {
        if (this.heartbeatTimer) {
            clearInterval(this.heartbeatTimer)
            this.heartbeatTimer = null
        }
        this.clearHeartbeatTimeout()
        this.isWaitingPong = false
    }

    /**
     * 清除心跳超时定时器
     */
    private clearHeartbeatTimeout(): void {
        if (this.heartbeatTimeoutTimer) {
            clearTimeout(this.heartbeatTimeoutTimer)
            this.heartbeatTimeoutTimer = null
        }
    }

    /**
     * 重连
     */
    private reconnect(): void {
        if (this.reconnectTimer || this.isManualClose) {
            return
        }

        if (this.reconnectAttempts >= this.maxReconnectAttempts) {
            return
        }

        this.reconnectAttempts++

        this.reconnectTimer = window.setTimeout(() => {
            this.reconnectTimer = null
            this.connect()
        }, this.reconnectInterval)
    }

    /**
     * 处理页面可见性变化
     */
    private handleVisibilityChange(): void {
        if (document.hidden) {
            // 页面隐藏，保持 WebSocket 连接
        } else {
            // 页面显示，检查连接状态
            if (!this.ws || this.ws.readyState !== WebSocket.OPEN) {
                this.connect()
            }
        }
    }

    /**
     * 处理页面卸载前事件
     */
    private handleBeforeUnload(): void {
        // 页面刷新时不标记为手动关闭，以便重连
    }

    /**
     * 添加消息处理器
     */
    onMessage(handler: (data: unknown) => void): () => void {
        this.messageHandlers.add(handler)

        // 返回取消订阅函数
        return () => {
            this.messageHandlers.delete(handler)
        }
    }

    /**
     * 添加连接状态变化处理器
     */
    onStatusChange(handler: (connected: boolean) => void): () => void {
        this.statusHandlers.add(handler)

        // 返回取消订阅函数
        return () => {
            this.statusHandlers.delete(handler)
        }
    }

    /**
     * 通知连接状态变化
     */
    private notifyStatusChange(connected: boolean): void {
        this.statusHandlers.forEach(handler => {
            try {
                handler(connected)
            } catch (error) {
                // 状态变化处理失败，静默处理
            }
        })
    }

    /**
     * 手动关闭连接
     */
    close(): void {
        this.isManualClose = true
        this.stopHeartbeat()

        if (this.reconnectTimer) {
            clearTimeout(this.reconnectTimer)
            this.reconnectTimer = null
        }

        if (this.ws) {
            this.ws.close()
            this.ws = null
        }
    }

    /**
     * 获取连接状态
     */
    getReadyState(): number {
        return this.ws ? this.ws.readyState : WebSocket.CLOSED
    }

    /**
     * 是否已连接
     */
    isConnected(): boolean {
        return this.ws !== null && this.ws.readyState === WebSocket.OPEN
    }
}

// 创建单例实例
const wsUrl = import.meta.env.VITE_WS_URL || 'ws://localhost:8080/ws'
export const websocketManager = new WebSocketManager(wsUrl)

export default WebSocketManager
