<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible && noteDetail" class="detail-overlay" @click="handleOverlayClick">
        <div class="detail-container" @click.stop>
          <!-- 加载状态 -->
          <div v-if="loading" class="loading-wrapper">
            <div class="loading-spinner"></div>
            <p>加载中...</p>
          </div>

          <div v-else class="detail-content">
            <!-- 左侧图片区域 -->
            <div class="image-section">
              <img :src="noteDetail.note.image || 'https://picsum.photos/800/600?random=0'" :alt="noteDetail.note.title" class="main-image" />
            </div>

            <!-- 右侧内容区域 -->
            <div class="info-section">
              <!-- 作者信息 -->
              <div class="author-header">
                <div class="author-info">
                  <img :src="noteDetail.user?.image || 'https://i.pravatar.cc/150?img=1'" :alt="noteDetail.user?.nickname" class="author-avatar" />
                  <div class="author-details">
                    <h3 class="author-name">{{ noteDetail.user?.nickname || '匿名用户' }}</h3>
                    <p class="publish-time">{{ noteDetail.dealTime || noteDetail.note.time || '刚刚' }}</p>
                  </div>
                </div>
                <button 
                  class="follow-btn" 
                  :class="{ followed: isFollowed }"
                  @click="toggleFollow"
                >
                  <Icon :icon="isFollowed ? 'mdi:check' : 'mdi:plus'" width="16" />
                  {{ isFollowed ? '已关注' : '关注' }}
                </button>
              </div>

              <!-- 笔记内容 -->
              <div class="note-body">
                <h2 class="note-title">{{ noteDetail.note.title }}</h2>
                <div class="note-content" v-html="cleanHtml"></div>

                <!-- 标签 -->
                <div v-if="noteDetail.note.type" class="note-tags">
                  <span class="tag-item">
                    <Icon icon="mdi:pound" width="14" />
                    {{ noteDetail.note.type }}
                  </span>
                </div>

                <!-- 地址信息 -->
                <div v-if="noteDetail.note.address" class="note-location">
                  <Icon icon="mdi:map-marker" width="16" />
                  <span>{{ noteDetail.note.address }}</span>
                </div>
              </div>

              <!-- 评论区域 -->
              <div class="comments-section">
                <div class="comments-header">
                  <h3>评论 {{ commentCount }}</h3>
                </div>

                <div class="comments-list">
                  <div v-if="loadingComments" class="loading-comments">
                    <Icon icon="mdi:loading" class="loading-icon" width="32" />
                    <p>加载评论中...</p>
                  </div>

                  <div v-else-if="comments.length === 0" class="empty-comments">
                    <Icon icon="mdi:comment-outline" width="48" color="#ccc" />
                    <p>暂无评论，快来抢沙发吧~</p>
                  </div>

                  <div v-else>
                    <div v-for="commentVo in comments" :key="commentVo.commentVoId" class="comment-item">
                      <img 
                        :src="commentVo.user?.image || 'https://i.pravatar.cc/150?img=1'" 
                        :alt="commentVo.user?.nickname || '匿名用户'" 
                        class="comment-avatar" 
                      />
                      <div class="comment-content">
                        <div class="comment-header">
                          <span class="comment-author">{{ commentVo.user?.nickname || commentVo.user?.username || '匿名用户' }}</span>
                          <span class="comment-time">{{ commentVo.dealTime || commentVo.comment.time }}</span>
                        </div>
                        <p class="comment-text">{{ commentVo.comment.content }}</p>
                        <div class="comment-actions">
                          <button class="comment-action-btn" @click="replyToComment(commentVo)">
                            <Icon icon="mdi:reply" width="14" />
                            回复
                          </button>
                        </div>

                        <!-- 子评论列表 -->
                        <div v-if="commentVo.childrenList && commentVo.childrenList.length > 0" class="children-comments">
                          <div v-for="childVo in commentVo.childrenList" :key="childVo.commentVoId" class="comment-item child-comment">
                            <img 
                              :src="childVo.user?.image || 'https://i.pravatar.cc/150?img=1'" 
                              :alt="childVo.user?.nickname || '匿名用户'" 
                              class="comment-avatar" 
                            />
                            <div class="comment-content">
                              <div class="comment-header">
                                <span class="comment-author">{{ childVo.user?.nickname || childVo.user?.username || '匿名用户' }}</span>
                                <span class="comment-time">{{ childVo.dealTime || childVo.comment.time }}</span>
                              </div>
                              <p class="comment-text">{{ childVo.comment.content }}</p>
                              <div class="comment-actions">
                                <button class="comment-action-btn" @click="replyToComment(commentVo)">
                                  <Icon icon="mdi:reply" width="14" />
                                  回复
                                </button>
                              </div>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 底部操作栏 -->
              <div class="action-bar">
                <!-- 回复提示 -->
                <div v-if="replyingTo" class="reply-indicator">
                  <span>回复 @{{ replyingTo.user?.nickname || replyingTo.user?.username || '匿名用户' }}</span>
                  <button class="cancel-reply-btn" @click="cancelReply">
                    <Icon icon="mdi:close" width="16" />
                  </button>
                </div>
                
                <div class="input-wrapper">
                  <input
                    v-model="commentInput"
                    type="text"
                    :placeholder="replyingTo ? '输入回复内容...' : '说点什么...'"
                    class="comment-input"
                    :disabled="submittingComment"
                    @keyup.enter="submitComment"
                  />
                  <button class="emoji-btn" :disabled="submittingComment">
                    <Icon icon="mdi:emoticon-outline" width="20" />
                  </button>
                </div>
                <button 
                  class="send-btn" 
                  :disabled="!commentInput.trim() || submittingComment"
                  @click="submitComment"
                >
                  <Icon v-if="submittingComment" icon="mdi:loading" class="loading-icon" width="16" />
                  <span v-else>发送</span>
                </button>
                
                <!-- 互动操作按钮（合并统计和操作） -->
                <div class="action-buttons">
                  <button class="action-btn like-btn" :class="{ active: isLiked }" @click="toggleLike">
                    <Icon :icon="isLiked ? 'mdi:heart' : 'mdi:heart-outline'" width="22" />
                    <span>{{ noteDetail.note.like || 0 }}</span>
                  </button>
                  <button class="action-btn collect-btn" :class="{ active: isCollected }" @click="toggleCollect">
                    <Icon :icon="isCollected ? 'mdi:star' : 'mdi:star-outline'" width="22" />
                    <span>{{ noteDetail.note.collection || 0 }}</span>
                  </button>
                  <button class="action-btn comment-btn">
                    <Icon icon="mdi:comment-outline" width="22" />
                    <span>{{ commentCount }}</span>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { Icon } from '@iconify/vue'
import DOMPurify from 'dompurify'
import { getNoteDetail, toggleLike as apiToggleLike, checkIsLiked, toggleCollection as apiToggleCollection, checkIsCollected, type NoteVo } from '../api/note'
import { getCommentList, getCommentCount, postComment, type CommentVo } from '../api/comment'
import { toggleFollow as apiToggleFollow, checkIsFollowed } from '../api/auth'
import { useToast } from '../composables/useToast'

const { showToast } = useToast()

interface Props {
  visible: boolean
  noteId: string | null
}

interface Emits {
  (e: 'update:visible', value: boolean): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const noteDetail = ref<NoteVo | null>(null)
const loading = ref(false)
const commentInput = ref('')
const isLiked = ref(false)
const isCollected = ref(false)
const isFollowed = ref(false)
const comments = ref<CommentVo[]>([])
const commentCount = ref(0)
const loadingComments = ref(false)
const submittingComment = ref(false)
const replyingTo = ref<CommentVo | null>(null)

// 清理 HTML 内容，防止 XSS 攻击
const cleanHtml = computed(() => DOMPurify.sanitize(noteDetail.value?.note?.content || ''))

// 加载笔记详情
const loadNoteDetail = async (noteId: string) => {
  try {
    loading.value = true
    const response = await getNoteDetail(noteId)
    if (response.code === 200 && response.data) {
      noteDetail.value = response.data
    }
  } catch (error) {
    // 加载笔记详情失败
  } finally {
    loading.value = false
  }
}

// 加载评论列表
const loadComments = async (noteId: string) => {
  try {
    loadingComments.value = true
    const response = await getCommentList(noteId)
    if (response.code === 200 && response.data) {
      comments.value = response.data
    }
  } catch (error) {
    comments.value = []
  } finally {
    loadingComments.value = false
  }
}

// 加载评论数量
const loadCommentCount = async (noteId: string) => {
  try {
    const response = await getCommentCount(noteId)
    if (response.code === 200 && response.data !== undefined) {
      commentCount.value = response.data
    }
  } catch (error) {
    commentCount.value = 0
  }
}

// 加载点赞状态
const loadLikeStatus = async (noteId: string) => {
  try {
    const response = await checkIsLiked(noteId)
    if (response.code === 200 && response.data !== undefined) {
      isLiked.value = response.data
    }
  } catch (error) {
    isLiked.value = false
  }
}

// 加载收藏状态
const loadCollectionStatus = async (noteId: string) => {
  try {
    const response = await checkIsCollected(noteId)
    if (response.code === 200 && response.data !== undefined) {
      isCollected.value = response.data
    }
  } catch (error) {
    isCollected.value = false
  }
}

// 加载关注状态
const loadFollowStatus = async () => {
  if (!noteDetail.value?.user?.id) {
    isFollowed.value = false
    return
  }
  
  try {
    const response = await checkIsFollowed(noteDetail.value.user.id)
    if (response.code === 200 && response.data !== undefined) {
      isFollowed.value = response.data === 1
    }
  } catch (error) {
    isFollowed.value = false
  }
}

// 监听 visible 和 noteId 变化，加载笔记详情和评论
watch(
  () => [props.visible, props.noteId],
  async ([visible, noteId]) => {
    if (visible && noteId) {
      await Promise.all([
        loadNoteDetail(noteId as string),
        loadComments(noteId as string),
        loadCommentCount(noteId as string),
        loadLikeStatus(noteId as string),
        loadCollectionStatus(noteId as string)
      ])
      // 加载关注状态（需要先加载笔记详情获取用户ID）
      await loadFollowStatus()
    }
  },
  { immediate: true }
)

const closeModal = () => {
  emit('update:visible', false)
}

const handleOverlayClick = () => {
  closeModal()
}

const toggleLike = async () => {
  if (!props.noteId) {
    return
  }

  // 检查是否登录
  const token = localStorage.getItem('token')
  if (!token) {
    showToast('请先登录后再点赞', 'error')
    return
  }

  try {
    const response = await apiToggleLike(props.noteId)
    
    if (response.code === 200) {
      // 切换点赞状态
      isLiked.value = !isLiked.value
      
      // 更新点赞数量
      if (noteDetail.value?.note) {
        const currentLikes = noteDetail.value.note.like || 0
        noteDetail.value.note.like = isLiked.value ? currentLikes + 1 : currentLikes - 1
      }
    } else {
      showToast(response.message || '操作失败', 'error')
    }
  } catch (error: unknown) {
    const errorMsg = error instanceof Error ? error.message : '操作失败，请稍后重试'
    showToast(errorMsg, 'error')
  }
}

const toggleCollect = async () => {
  if (!props.noteId) {
    return
  }

  // 检查是否登录
  const token = localStorage.getItem('token')
  if (!token) {
    showToast('请先登录后再收藏', 'error')
    return
  }

  try {
    const response = await apiToggleCollection(props.noteId)
    
    if (response.code === 200) {
      // 切换收藏状态
      isCollected.value = !isCollected.value
      
      // 更新收藏数量
      if (noteDetail.value?.note) {
        const currentCollections = noteDetail.value.note.collection || 0
        noteDetail.value.note.collection = isCollected.value ? currentCollections + 1 : currentCollections - 1
      }
    } else {
      showToast(response.message || '操作失败', 'error')
    }
  } catch (error: unknown) {
    const errorMsg = error instanceof Error ? error.message : '操作失败，请稍后重试'
    showToast(errorMsg, 'error')
  }
}

// 提交评论
const submitComment = async () => {
  if (!commentInput.value.trim()) {
    return
  }

  if (!props.noteId) {
    return
  }

  // 检查是否登录
  const token = localStorage.getItem('token')
  if (!token) {
    showToast('请先登录后再评论', 'error')
    return
  }

  try {
    submittingComment.value = true
    
    const parentId = replyingTo.value ? replyingTo.value.commentVoId : '0'
    
    const response = await postComment({
      noteId: props.noteId,
      content: commentInput.value.trim(),
      parentId: parentId
    })

    if (response.code === 200) {
      // 清空输入框和回复状态
      commentInput.value = ''
      replyingTo.value = null
      
      // 重新加载评论列表和数量
      await Promise.all([
        loadComments(props.noteId),
        loadCommentCount(props.noteId)
      ])
    } else {
      showToast(response.message || '评论发表失败', 'error')
    }
  } catch (error) {
    showToast('评论发表失败，请稍后重试', 'error')
  } finally {
    submittingComment.value = false
  }
}

// 回复评论
const replyToComment = (commentVo: CommentVo) => {
  replyingTo.value = commentVo
  commentInput.value = `@${commentVo.user?.nickname || commentVo.user?.username || '匿名用户'} `
  // 聚焦到输入框
  const inputElement = document.querySelector('.comment-input') as HTMLInputElement
  if (inputElement) {
    inputElement.focus()
  }
}

// 取消回复
const cancelReply = () => {
  replyingTo.value = null
  commentInput.value = ''
}

// 关注/取消关注作者
const toggleFollow = async () => {
  const token = localStorage.getItem('token')
  if (!token) {
    showToast('请先登录后再关注', 'error')
    return
  }
  
  if (!noteDetail.value?.user?.id) {
    showToast('用户信息不存在', 'error')
    return
  }
  
  try {
    const response = await apiToggleFollow(noteDetail.value.user.id)
    if (response.code === 200) {
      isFollowed.value = !isFollowed.value
      showToast(isFollowed.value ? '关注成功' : '已取消关注', 'success')
    } else {
      showToast(response.message || '操作失败', 'error')
    }
  } catch (error) {
    showToast('操作失败，请稍后重试', 'error')
  }
}
</script>

<style scoped>
.detail-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.75);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2000;
  padding: 20px;
}

.detail-container {
  position: relative;
  background: white;
  border-radius: 16px;
  width: 100%;
  max-width: 1400px;
  height: 90vh;
  max-height: 900px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
  overflow: hidden;
  animation: slideUp 0.3s ease-out;
}

.loading-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #999;
}

.loading-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid #f0f0f0;
  border-top-color: #ff2442;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.loading-wrapper p {
  margin-top: 16px;
  font-size: 14px;
}

@keyframes slideUp {
  from {
    opacity: 0;
    transform: scale(0.95);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

.detail-content {
  display: flex;
  height: 100%;
}

/* 左侧图片区域 */
.image-section {
  flex: 0 0 50%;
  background: #000;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.main-image {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

/* 右侧内容区域 */
.info-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  background: white;
}

.author-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px;
  border-bottom: 1px solid #f0f0f0;
}

.author-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.author-avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
}

.author-details {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.author-name {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  margin: 0;
}

.publish-time {
  font-size: 12px;
  color: #999;
  margin: 0;
}

.follow-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 20px;
  background: #ff2442;
  color: white;
  border: none;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
}

.follow-btn:hover {
  background: #e61e3a;
  transform: translateY(-1px);
}

.follow-btn.followed {
  background: #f0f0f0;
  color: #666;
}

.follow-btn.followed:hover {
  background: #e5e5e5;
}

/* 笔记内容 */
.note-body {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
}

.note-title {
  font-size: 20px;
  font-weight: 600;
  color: #333;
  margin: 0 0 16px 0;
  line-height: 1.4;
}

.note-content {
  font-size: 14px;
  color: #666;
  line-height: 1.8;
  margin-bottom: 16px;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 适配后端返回的话题样式 */
.note-content :deep(span) {
  color: inherit;
}

.note-content .topic-tag,
.note-content :deep(span[style*="color"]) {
  color: #ff2442 !important;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.2s;
}

.note-content .topic-tag:hover,
.note-content :deep(span[style*="color"]:hover) {
  opacity: 0.8;
}

.note-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.tag-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  background: #f7f7f7;
  color: #ff2442;
  border-radius: 16px;
  font-size: 13px;
  font-weight: 500;
}

.note-location {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #666;
  font-size: 13px;
  margin-bottom: 16px;
}

/* 评论区域 */
.comments-section {
  flex: 1;
  display: flex;
  flex-direction: column;
  border-top: 1px solid #f0f0f0;
  overflow: hidden;
}

.comments-header {
  padding: 16px 20px;
  border-bottom: 1px solid #f0f0f0;
}

.comments-header h3 {
  font-size: 15px;
  font-weight: 600;
  color: #333;
  margin: 0;
}

.comments-list {
  flex: 1;
  padding: 16px 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}

.empty-comments {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: #999;
}

.loading-comments {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: #999;
}

.loading-comments .loading-icon {
  animation: spin 1s linear infinite;
  color: #ff2442;
  margin-bottom: 12px;
}

.empty-comments p {
  margin-top: 12px;
  font-size: 14px;
}

.comment-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
}

.children-comments {
  margin-top: 12px;
  padding-left: 48px;
  border-left: 2px solid #f0f0f0;
}

.child-comment {
  margin-bottom: 16px;
}

.child-comment:last-child {
  margin-bottom: 0;
}

.child-comment .comment-avatar {
  width: 32px;
  height: 32px;
}

.child-comment .comment-text {
  font-size: 13px;
}

.comment-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.comment-content {
  flex: 1;
}

.comment-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}

.comment-author {
  font-size: 13px;
  font-weight: 500;
  color: #333;
}

.comment-time {
  font-size: 12px;
  color: #999;
}

.comment-text {
  font-size: 14px;
  color: #666;
  line-height: 1.6;
  margin: 0 0 8px 0;
}

.comment-actions {
  display: flex;
  gap: 16px;
}

.comment-action-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  background: none;
  border: none;
  color: #999;
  font-size: 12px;
  cursor: pointer;
  transition: color 0.2s;
}

.comment-action-btn:hover {
  color: #ff2442;
}

/* 底部操作栏 */
.action-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border-top: 1px solid #f0f0f0;
  background: white;
  flex-wrap: wrap;
}

.reply-indicator {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #fff8e1;
  border-radius: 8px;
  font-size: 13px;
  color: #666;
  margin-bottom: 8px;
}

.cancel-reply-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px;
  background: transparent;
  border: none;
  color: #999;
  cursor: pointer;
  border-radius: 50%;
  transition: all 0.2s;
}

.cancel-reply-btn:hover {
  background: rgba(0, 0, 0, 0.05);
  color: #666;
}

.input-wrapper {
  flex: 1;
  display: flex;
  align-items: center;
  background: #f7f7f7;
  border-radius: 20px;
  padding: 8px 16px;
  min-width: 0;
}

.comment-input {
  flex: 1;
  border: none;
  background: transparent;
  outline: none;
  font-size: 14px;
  color: #333;
  min-width: 0;
}

.comment-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.comment-input::placeholder {
  color: #999;
}

.emoji-btn {
  padding: 4px;
  background: none;
  border: none;
  color: #999;
  cursor: pointer;
  display: flex;
  align-items: center;
  transition: color 0.2s;
  flex-shrink: 0;
}

.emoji-btn:hover:not(:disabled) {
  color: #666;
}

.emoji-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.send-btn {
  padding: 8px 20px;
  background: #ff2442;
  color: white;
  border: none;
  border-radius: 20px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s;
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
}

.send-btn:hover:not(:disabled) {
  background: #e61e3a;
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-buttons {
  display: flex;
  gap: 12px;
  flex-shrink: 0;
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  border: none;
  background: #f7f7f7;
  border-radius: 24px;
  cursor: pointer;
  color: #666;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.2s;
  white-space: nowrap;
}

.action-btn:hover {
  background: #e5e5e5;
  transform: translateY(-1px);
}

.action-btn.like-btn.active {
  color: #ff2442;
  background: #fff0f0;
}

.action-btn.collect-btn.active {
  color: #ffa500;
  background: #fff8e1;
}

.action-btn span {
  font-size: 14px;
}

.comment-btn {
  cursor: default;
}

.comment-btn:hover {
  transform: none;
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
@media (max-width: 1200px) {
  .detail-container {
    max-width: 95%;
  }

  .image-section {
    flex: 0 0 45%;
  }
}

@media (max-width: 900px) {
  .detail-container {
    max-width: 100%;
    height: 100vh;
    max-height: 100vh;
    border-radius: 0;
  }

  .detail-content {
    flex-direction: column;
  }

  .image-section {
    flex: 0 0 50%;
  }

  .info-section {
    flex: 1;
  }
}

@media (max-width: 600px) {
  .image-section {
    flex: 0 0 40%;
  }

  .author-header {
    padding: 16px;
  }

  .note-body {
    padding: 16px;
  }

  .comments-list {
    padding: 12px 16px;
  }

  .action-bar {
    padding: 12px 16px;
    flex-wrap: wrap;
  }

  .action-buttons {
    width: 100%;
    justify-content: space-between;
    margin-top: 8px;
    gap: 8px;
  }

  .action-btn {
    padding: 8px 12px;
    font-size: 13px;
  }
}
</style>
