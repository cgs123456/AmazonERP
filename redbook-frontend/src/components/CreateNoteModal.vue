<template>
  <Teleport to="body">
    <Transition name="modal" @after-leave="handleAfterLeave">
      <div v-if="visible" class="modal-overlay" @click="handleClose">
        <div class="modal-container" @click.stop>
          <div class="modal-header">
            <h3>发布笔记</h3>
            <button class="close-btn" @click="handleClose">
              <Icon icon="mdi:close" width="24" />
            </button>
          </div>

          <div class="modal-body">
            <!-- 标题输入 -->
            <div class="form-group">
              <label class="form-label">标题</label>
              <input 
                v-model="formData.title"
                type="text" 
                class="form-input"
                placeholder="请输入标题..."
                maxlength="50"
              />
              <div class="char-count">{{ formData.title.length }}/50</div>
            </div>

            <!-- 内容输入 -->
            <div class="form-group">
              <label class="form-label">内容</label>
              <div class="content-wrapper">
                <textarea 
                  ref="contentTextarea"
                  v-model="formData.content"
                  class="form-textarea"
                  placeholder="分享你的想法..."
                  maxlength="1000"
                  rows="6"
                  @input="handleContentInput"
                ></textarea>
                <div class="content-footer">
                  <div class="topic-btn-wrapper">
                    <button 
                      class="topic-btn" 
                      @click="toggleTopicPopup"
                      type="button"
                    >
                      <Icon icon="mdi:pound" width="18" />
                      话题
                    </button>
                    
                    <!-- 话题选择弹窗 -->
                    <Transition name="popup">
                      <div v-if="showTopicPopup" class="topic-popup" @click.stop>
                        <div class="topic-popup-header">
                          <span>选择话题</span>
                          <button class="popup-close" @click="showTopicPopup = false">
                            <Icon icon="mdi:close" width="16" />
                          </button>
                        </div>
                        <div class="topic-search">
                          <input 
                            v-model="topicSearch"
                            type="text"
                            placeholder="搜索或创建话题..."
                            class="topic-search-input"
                            @keyup.enter="selectOrCreateTopic"
                          />
                        </div>
                        <div class="topic-list">
                          <div 
                            v-for="topic in filteredTopics" 
                            :key="topic"
                            class="topic-item"
                            @click="selectTopic(topic)"
                          >
                            <Icon icon="mdi:pound" width="16" />
                            {{ topic }}
                          </div>
                          <div 
                            v-if="topicSearch && !suggestedTopics.includes(topicSearch)"
                            class="topic-item topic-create"
                            @click="selectOrCreateTopic"
                          >
                            <Icon icon="mdi:plus-circle" width="16" />
                            创建 "{{ topicSearch }}"
                          </div>
                          <div v-if="filteredTopics.length === 0 && !topicSearch" class="topic-empty">
                            暂无推荐话题
                          </div>
                        </div>
                      </div>
                    </Transition>
                  </div>
                  <div class="char-count">{{ formData.content.length }}/1000</div>
                </div>
              </div>
            </div>

            <!-- 图片上传 -->
            <div class="form-group">
              <label class="form-label">图片</label>
              <div class="image-upload">
                <div v-if="imagePreview" class="image-preview">
                  <img :src="imagePreview" alt="预览" />
                  <button class="remove-image" @click="removeImage">
                    <Icon icon="mdi:close-circle" width="24" />
                  </button>
                </div>
                <div v-else class="upload-area" @click="triggerFileInput">
                  <Icon icon="mdi:image-plus" width="48" />
                  <p>点击上传图片</p>
                  <input 
                    ref="fileInput"
                    type="file" 
                    accept="image/*"
                    @change="handleFileChange"
                    style="display: none"
                  />
                </div>
              </div>
            </div>

            <!-- 已选话题 -->
            <div class="form-group" v-if="selectedTopics.length > 0">
              <label class="form-label">已添加话题</label>
              <div class="topics-list">
                <span 
                  v-for="(topic, index) in selectedTopics" 
                  :key="index"
                  class="topic-tag"
                >
                  #{{ topic }}
                  <button class="remove-topic" @click="removeSelectedTopic(index)">
                    <Icon icon="mdi:close" width="14" />
                  </button>
                </span>
              </div>
            </div>
          </div>

          <div class="modal-footer">
            <button class="btn btn-cancel" @click="handleClose">取消</button>
            <button 
              class="btn btn-publish" 
              :disabled="!canPublish || loading"
              @click="handlePublish"
            >
              {{ loading ? '发布中...' : '发布' }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { getTopics } from '../api/topic'
import { postNote } from '../api/note'
import { useToast } from '../composables/useToast'

const { showToast } = useToast()

interface Props {
  visible: boolean
}

interface Emits {
  (e: 'update:visible', value: boolean): void
  (e: 'success'): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const formData = ref({
  title: '',
  content: '',
  topics: [] as string[]
})

const imageFile = ref<File | null>(null)
const imagePreview = ref<string>('')
const loading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const contentTextarea = ref<HTMLTextAreaElement | null>(null)

// 话题相关
const showTopicPopup = ref(false)
const topicSearch = ref('')
const selectedTopics = ref<string[]>([])
const suggestedTopics = ref<string[]>([])

// 获取话题列表
const fetchTopics = async () => {
  try {
    const result = await getTopics()
    if (result.code === 200 && result.data) {
      // 按热度排序并提取 content 字段
      suggestedTopics.value = result.data
        .sort((a, b) => (b.hot || 0) - (a.hot || 0))
        .map((topic) => topic.content || '')
        .filter(content => content) // 过滤空值
    }
  } catch (error) {
    // 获取话题列表失败，使用默认话题
    suggestedTopics.value = ['美食', '旅行', '穿搭', '美妆', '健身', '摄影', '读书', '音乐']
  }
}

const filteredTopics = computed(() => {
  if (!topicSearch.value) {
    return suggestedTopics.value
  }
  return suggestedTopics.value.filter(topic => 
    topic.includes(topicSearch.value)
  )
})

// 监听弹窗关闭，重置表单
watch(() => props.visible, (newVisible) => {
  if (newVisible) {
    // 弹窗打开时获取话题列表
    fetchTopics()
  } else {
    setTimeout(() => {
      resetForm()
    }, 300)
  }
})

// 是否可以发布
const canPublish = computed(() => {
  return formData.value.title.trim() && 
         formData.value.content.trim() && 
         imageFile.value !== null
})

// 触发文件选择
const triggerFileInput = () => {
  fileInput.value?.click()
}

// 处理文件选择
const handleFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  
  if (file) {
    if (!file.type.startsWith('image/')) {
      showToast('请选择图片文件', 'error')
      return
    }
    
    if (file.size > 5 * 1024 * 1024) {
      showToast('图片大小不能超过5MB', 'error')
      return
    }
    
    imageFile.value = file
    
    // 创建预览
    const reader = new FileReader()
    reader.onload = (e) => {
      imagePreview.value = e.target?.result as string
    }
    reader.readAsDataURL(file)
  }
}

// 移除图片
const removeImage = () => {
  imageFile.value = null
  imagePreview.value = ''
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

// 切换话题弹窗
const toggleTopicPopup = () => {
  showTopicPopup.value = !showTopicPopup.value
  if (showTopicPopup.value) {
    topicSearch.value = ''
  }
}

// 处理内容输入
const handleContentInput = () => {
  // 检测是否输入了 #
  const content = formData.value.content
  if (content.endsWith('#')) {
    showTopicPopup.value = true
  }
}

// 选择话题
const selectTopic = (topic: string) => {
  if (selectedTopics.value.length >= 5) {
    showToast('最多添加5个话题', 'error')
    return
  }
  
  if (!selectedTopics.value.includes(topic)) {
    selectedTopics.value.push(topic)
    
    // 在内容中插入话题
    const textarea = contentTextarea.value
    if (textarea) {
      const cursorPos = textarea.selectionStart
      const textBefore = formData.value.content.substring(0, cursorPos)
      const textAfter = formData.value.content.substring(cursorPos)
      
      // 如果最后一个字符是 #，替换它
      if (textBefore.endsWith('#')) {
        formData.value.content = textBefore.slice(0, -1) + `#${topic} ` + textAfter
      } else {
        formData.value.content = textBefore + `#${topic} ` + textAfter
      }
    }
  }
  
  showTopicPopup.value = false
  topicSearch.value = ''
}

// 选择或创建话题
const selectOrCreateTopic = () => {
  const topic = topicSearch.value.trim()
  if (topic) {
    selectTopic(topic)
  }
}

// 移除已选话题
const removeSelectedTopic = (index: number) => {
  const topic = selectedTopics.value[index]
  selectedTopics.value.splice(index, 1)
  
  // 从内容中移除话题标签
  formData.value.content = formData.value.content.replace(new RegExp(`#${topic}\\s*`, 'g'), '')
}

// 重置表单
const resetForm = () => {
  formData.value = {
    title: '',
    content: '',
    topics: []
  }
  imageFile.value = null
  imagePreview.value = ''
  selectedTopics.value = []
  showTopicPopup.value = false
  topicSearch.value = ''
  if (fileInput.value) {
    fileInput.value.value = ''
  }
}

// 关闭弹窗
const handleClose = () => {
  emit('update:visible', false)
}

// Transition after-leave 回调
const handleAfterLeave = () => {
  resetForm()
}

// 发布笔记
const handlePublish = async () => {
  if (!canPublish.value || loading.value) return
  
  try {
    loading.value = true
    
    // 构建 FormData
    const formDataToSend = new FormData()
    formDataToSend.append('image', imageFile.value!)
    formDataToSend.append('title', formData.value.title)
    formDataToSend.append('content', formData.value.content)
    formDataToSend.append('longitude', '0')
    formDataToSend.append('latitude', '0')
    
    // 添加话题
    selectedTopics.value.forEach(topic => {
      formDataToSend.append('topics', topic)
    })

    // 调用发布接口
    const result = await postNote(formDataToSend)
    
    if (result.code === 200) {
      showToast('发布成功！', 'success')
      emit('success')
      handleClose()
    } else {
      showToast(result.message || '发布失败', 'error')
    }
  } catch (error) {
    showToast('发布失败，请稍后重试', 'error')
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
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10000;
  padding: 20px;
}

.modal-container {
  background: white;
  border-radius: 16px;
  width: 100%;
  max-width: 600px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid #f0f0f0;
}

.modal-header h3 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #333;
}

.close-btn {
  width: 32px;
  height: 32px;
  border: none;
  background: #f5f5f5;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
  color: #666;
}

.close-btn:hover {
  background: #e9ecef;
}

.modal-body {
  flex: 1;
  padding: 24px;
  overflow-y: auto;
}

.form-group {
  margin-bottom: 24px;
  position: relative;
}

.form-label {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: #333;
  margin-bottom: 8px;
}

.form-input {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
}

.form-input:focus {
  border-color: #ff2442;
}

.form-textarea {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
  resize: vertical;
  font-family: inherit;
}

.form-textarea:focus {
  border-color: #ff2442;
}

.char-count {
  font-size: 12px;
  color: #999;
}

/* 内容区域 */
.content-wrapper {
  position: relative;
}

.content-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8px;
}

.topic-btn-wrapper {
  position: relative;
}

.topic-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: 1px solid #ddd;
  background: white;
  border-radius: 16px;
  font-size: 13px;
  color: #666;
  cursor: pointer;
  transition: all 0.2s;
}

.topic-btn:hover {
  border-color: #ff2442;
  color: #ff2442;
}

/* 话题弹窗 */
.topic-popup {
  position: absolute;
  top: 100%;
  left: 0;
  margin-top: 8px;
  width: 300px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.15);
  z-index: 100;
  overflow: hidden;
}

.topic-popup-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 14px;
  font-weight: 600;
  color: #333;
}

.popup-close {
  border: none;
  background: none;
  color: #999;
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  transition: color 0.2s;
}

.popup-close:hover {
  color: #333;
}

.topic-search {
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
}

.topic-search-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 13px;
  outline: none;
  transition: border-color 0.2s;
}

.topic-search-input:focus {
  border-color: #ff2442;
}

.topic-list {
  max-height: 240px;
  overflow-y: auto;
}

.topic-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  font-size: 14px;
  color: #333;
  cursor: pointer;
  transition: background 0.2s;
}

.topic-item:hover {
  background: #f8f9fa;
}

.topic-create {
  color: #ff2442;
  font-weight: 500;
}

.topic-empty {
  padding: 24px 16px;
  text-align: center;
  font-size: 13px;
  color: #999;
}

/* 弹窗动画 */
.popup-enter-active,
.popup-leave-active {
  transition: all 0.2s;
}

.popup-enter-from,
.popup-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* 图片上传 */
.image-upload {
  margin-top: 8px;
}

.upload-area {
  border: 2px dashed #ddd;
  border-radius: 8px;
  padding: 40px;
  text-align: center;
  cursor: pointer;
  transition: all 0.2s;
  color: #999;
}

.upload-area:hover {
  border-color: #ff2442;
  color: #ff2442;
}

.upload-area p {
  margin: 8px 0 0 0;
  font-size: 14px;
}

.image-preview {
  position: relative;
  width: 100%;
  max-width: 400px;
  border-radius: 8px;
  overflow: hidden;
}

.image-preview img {
  width: 100%;
  height: auto;
  display: block;
}

.remove-image {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 32px;
  height: 32px;
  border: none;
  background: rgba(0, 0, 0, 0.6);
  color: white;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s;
}

.remove-image:hover {
  background: rgba(0, 0, 0, 0.8);
}

/* 话题 */
.topics-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.topic-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: #fff5f5;
  color: #ff2442;
  border-radius: 16px;
  font-size: 13px;
  font-weight: 500;
}

.remove-topic {
  border: none;
  background: none;
  color: #ff2442;
  cursor: pointer;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.2s;
}

.remove-topic:hover {
  opacity: 0.7;
}

/* 底部按钮 */
.modal-footer {
  display: flex;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid #f0f0f0;
}

.btn {
  flex: 1;
  height: 44px;
  border: none;
  border-radius: 22px;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.3s;
}

.btn-cancel {
  background: #f5f5f5;
  color: #666;
}

.btn-cancel:hover {
  background: #e9ecef;
}

.btn-publish {
  background: linear-gradient(135deg, #ff2442 0%, #ff4757 100%);
  color: white;
}

.btn-publish:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(255, 36, 66, 0.3);
}

.btn-publish:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

/* 动画 */
.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.3s;
}

.modal-enter-active .modal-container,
.modal-leave-active .modal-container {
  transition: transform 0.3s;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}

.modal-enter-from .modal-container,
.modal-leave-to .modal-container {
  transform: scale(0.9);
}

/* 响应式 */
@media (max-width: 600px) {
  .modal-container {
    max-width: 100%;
    margin: 0 16px;
  }
}
</style>
