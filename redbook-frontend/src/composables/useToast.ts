import { ref } from 'vue'

const toastVisible = ref(false)
const toastMessage = ref('')
const toastType = ref<'success' | 'error' | 'info'>('info')
let timer: ReturnType<typeof setTimeout> | null = null

export function useToast() {
    const showToast = (msg: string, type: 'success' | 'error' | 'info' = 'info', duration = 3000) => {
        toastMessage.value = msg
        toastType.value = type
        toastVisible.value = true
        if (timer) clearTimeout(timer)
        timer = setTimeout(() => {
            toastVisible.value = false
        }, duration)
    }

    return { toastVisible, toastMessage, toastType, showToast }
}
