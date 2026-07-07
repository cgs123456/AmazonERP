<template>
  <div class="home-page">
    <!-- 顶部导航栏 -->
    <AppHeader />

    <!-- 侧边栏 -->
    <AppSidebar />

    <!-- 主内容区 -->
    <main class="main-content">
      <!-- 分类标签 -->
      <nav class="category-nav">
        <div class="category-scroll">
          <button 
            v-for="category in categories" 
            :key="category"
            :class="['category-item', { active: activeCategory === category }]"
            @click="switchCategory(category)"
          >
            <Icon v-if="category === '推荐'" icon="mdi:fire" width="16" />
            {{ category }}
          </button>
        </div>
      </nav>

      <!-- 瀑布流笔记列表 -->
      <div class="waterfall-container">
        <div v-if="loading && notes.length === 0" class="loading-container">
          <Icon icon="mdi:loading" class="loading-icon" width="48" />
          <p>加载中...</p>
        </div>
        
        <div v-else class="waterfall-columns">
          <div class="waterfall-column" v-for="(column, index) in columns" :key="index">
            <div class="note-card" v-for="note in column" :key="note.id" @click="openNoteDetail(note)">
              <div class="note-image-wrapper">
                <img 
                  :src="note.image" 
                  :alt="note.title" 
                  class="note-image" 
                  loading="lazy"
                />
                <div class="image-overlay">
                  <span v-if="note.tag" class="note-tag">
                    <Icon icon="mdi:tag" width="12" />
                    {{ note.tag }}
                  </span>
                </div>
              </div>
              <div class="note-content">
                <h3 class="note-title">{{ note.title }}</h3>
                <div class="note-meta">
                  <div class="author-info">
                    <img :src="note.avatar" :alt="note.author" class="author-avatar" />
                    <span class="author-name">{{ note.author }}</span>
                  </div>
                  <div class="note-actions">
                    <span class="like-count">
                      <Icon icon="mdi:heart-outline" width="16" />
                      {{ note.likes }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- 登录弹窗 -->
    <LoginModal
      v-model:visible="showLoginModal"
      @login-success="handleLoginSuccess"
    />

    <!-- 笔记详情弹窗 -->
    <NoteDetailModal
      v-model:visible="showDetailModal"
      :note-id="selectedNoteId"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import LoginModal from '../components/LoginModal.vue'
import NoteDetailModal from '../components/NoteDetailModal.vue'
import { getNoteList, type NoteVo } from '../api/note'
import { useToast } from '../composables/useToast'

const { showToast } = useToast()

interface Note {
  id: string // 使用 string 类型存储后端的 long ID
  image: string
  title: string
  content?: string
  author: string
  avatar: string
  likes: string
  collection?: number
  tag?: string
  time?: string
  address?: string
  height: number
}

const showLoginModal = ref(false)
const showDetailModal = ref(false)
const selectedNoteId = ref<string | null>(null)
const notes = ref<Note[]>([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const activeCategory = ref('推荐')
const hasMore = ref(true)

const columns = ref<Note[][]>([[], [], [], []])

// 分类列表
const categories = ['推荐', '穿搭', '美妆', '影视', '职场', '美食', '旅行', '家居', '游戏', '汽车', '运动', '摄影']

// 格式化点赞数
const formatLikeCount = (count: number | undefined | null): string => {
  if (!count && count !== 0) {
    return '0'
  }
  if (count >= 10000) {
    return (count / 10000).toFixed(1) + 'w'
  } else if (count >= 1000) {
    return (count / 1000).toFixed(1) + 'k'
  }
  return count.toString()
}

// 计算图片高度（根据标题长度和随机因素）
const calculateHeight = (index: number): number => {
  const heights = [380, 400, 420, 440, 460]
  return heights[index % heights.length]
}

// 转换后端数据为前端格式
const convertNoteData = (noteVo: NoteVo, index: number): Note => {
  // 从嵌套的 note 对象中获取数据
  const noteData = noteVo.note
  
  // 获取作者信息
  const authorName = noteVo.user?.nickname || noteVo.user?.username || '匿名用户'
  // 将 ID 转换为数字用于生成头像（如果 ID 是纯数字字符串）
  const numericId = parseInt(noteData.id) || index
  const authorAvatar = noteVo.user?.image || `https://i.pravatar.cc/150?img=${(numericId % 70) + 1}`
  
  // 确保使用真实的 ID
  if (!noteData.id) {
  }
  
  return {
    id: noteData.id, // 使用真实的后端 ID（string 类型）
    image: noteData.image || `https://picsum.photos/300/${calculateHeight(index)}?random=${numericId}`,
    title: noteData.title || '无标题',
    content: noteData.content,
    author: authorName,
    avatar: authorAvatar,
    likes: formatLikeCount(noteData.like), // 后端字段是 like
    collection: noteData.collection,
    tag: noteData.type, // 使用 type 作为标签
    time: noteData.time,
    address: noteData.address,
    height: calculateHeight(index)
  }
}

// 打开笔记详情
const openNoteDetail = (note: Note) => {
  // 确保笔记有有效的 ID
  if (!note.id) {
    showToast('无法打开笔记详情：笔记 ID 无效', 'error')
    return
  }

  // 直接传递笔记 ID，让详情组件自己去请求数据
  selectedNoteId.value = note.id
  showDetailModal.value = true
}

// 加载笔记列表
const loadNotes = async () => {
  if (loading.value || !hasMore.value) return

  try {
    loading.value = true
    const response = await getNoteList(currentPage.value, pageSize.value)

    if (response.code === 200 && response.data) {
      const newNotes = response.data.map((note, index) => convertNoteData(note, index))
      notes.value = [...notes.value, ...newNotes]
      layoutWaterfall()
      
      // 如果返回的数据少于每页数量，说明没有更多数据了
      if (response.data.length < pageSize.value) {
        hasMore.value = false
      }
    } else {
      // 如果后端接口失败，使用模拟数据
      if (currentPage.value === 1) {
        loadMockData()
      }
      hasMore.value = false
    }
  } catch (error) {
    // 如果请求失败，使用模拟数据
    if (currentPage.value === 1) {
      loadMockData()
    }
    hasMore.value = false
  } finally {
    loading.value = false
  }
}

// 无限滚动处理
const handleScroll = () => {
  const scrollTop = document.documentElement.scrollTop
  const windowHeight = window.innerHeight
  const documentHeight = document.documentElement.scrollHeight
  
  // 距离底部 200px 时触发加载
  if (documentHeight - scrollTop - windowHeight < 200) {
    currentPage.value++
    loadNotes()
  }
}

// 切换分类
const switchCategory = (category: string) => {
  activeCategory.value = category
  // 重置列表并重新加载
  notes.value = []
  currentPage.value = 1
  hasMore.value = true
  loadNotes()
}

// 加载模拟数据（作为后备方案）
const loadMockData = () => {
  const mockNotes: Note[] = [
    {
      id: '1',
      image: 'https://picsum.photos/300/400?random=1',
      title: '分享我家的装修风格！超喜欢这个飘窗设计',
      author: '王小美',
      avatar: 'https://i.pravatar.cc/150?img=1',
      likes: '1.7k',
      height: 400
    },
    {
      id: '2',
      image: 'https://picsum.photos/300/500?random=2',
      title: '三口之家的幸福时光🌸这样的周末太治愈了',
      author: '幸福一家人',
      avatar: 'https://i.pravatar.cc/150?img=2',
      likes: '3.2k',
      tag: '生活',
      height: 500
    },
    {
      id: '3',
      image: 'https://picsum.photos/300/350?random=3',
      title: '💍 100万打造的婚礼现场！每个细节都超满意',
      author: '婚礼之家',
      avatar: 'https://i.pravatar.cc/150?img=3',
      likes: '5.1k',
      height: 350
    },
    {
      id: '4',
      image: 'https://picsum.photos/300/450?random=4',
      title: '把白娘子送到法国庄园🏰 这波操作绝了',
      author: '旅游达人',
      avatar: 'https://i.pravatar.cc/150?img=4',
      likes: '8.9k',
      tag: '旅行',
      height: 450
    },
    {
      id: '5',
      image: 'https://picsum.photos/300/380?random=5',
      title: '最爱拉丝奶酪🧀️ 这家店的芝士真的太绝了',
      author: '美食探店',
      avatar: 'https://i.pravatar.cc/150?img=5',
      likes: '2.3k',
      height: 380
    },
    {
      id: '6',
      image: 'https://picsum.photos/300/420?random=6',
      title: '美妆分享｜让皮肤变好的秘密都在这里了',
      author: '李美妆',
      avatar: 'https://i.pravatar.cc/150?img=6',
      likes: '1.8k',
      height: 420
    },
    {
      id: '7',
      image: 'https://picsum.photos/300/360?random=7',
      title: '二次元手办开箱！这个原神花散里太可爱了',
      author: '动漫爱好者',
      avatar: 'https://i.pravatar.cc/150?img=7',
      likes: '1.0k',
      height: 360
    },
    {
      id: '8',
      image: 'https://picsum.photos/300/480?random=8',
      title: '今天的穿搭分享💃 马卡龙色系超显白',
      author: '穿搭博主',
      avatar: 'https://i.pravatar.cc/150?img=8',
      likes: '1.5k',
      height: 480
    },
    {
      id: '9',
      image: 'https://picsum.photos/300/390?random=9',
      title: '不用烤箱也能做的甜品！新手必看',
      author: 'Super_Caroline',
      avatar: 'https://i.pravatar.cc/150?img=9',
      likes: '1.9k',
      height: 390
    },
    {
      id: '10',
      image: 'https://picsum.photos/300/440?random=10',
      title: '屏蔽国人吧，真的受够了他们的素质',
      author: '海外华人',
      avatar: 'https://i.pravatar.cc/150?img=10',
      likes: '6.0k',
      tag: '热门',
      height: 440
    },
    {
      id: '11',
      image: 'https://picsum.photos/300/370?random=11',
      title: '极简风格的家居设计，住进去太舒服了',
      author: '家居设计师',
      avatar: 'https://i.pravatar.cc/150?img=11',
      likes: '2.7k',
      height: 370
    },
    {
      id: '12',
      image: 'https://picsum.photos/300/460?random=12',
      title: '秋天的第一杯奶茶🍂 这家店必须安利',
      author: '奶茶爱好者',
      avatar: 'https://i.pravatar.cc/150?img=12',
      likes: '3.4k',
      height: 460
    }
  ]
  notes.value = mockNotes
  layoutWaterfall()
}

// 检查登录状态
const checkLoginStatus = () => {
  const token = localStorage.getItem('token')
  if (!token) {
    showLoginModal.value = true
  }
}

// 登录成功回调
const handleLoginSuccess = () => {
  // 登录成功后重新加载笔记列表
  notes.value = []
  currentPage.value = 1
  loadNotes()
}

// 瀑布流布局算法
const layoutWaterfall = () => {
  const columnHeights = [0, 0, 0, 0]
  columns.value = [[], [], [], []]
  
  notes.value.forEach(note => {
    const minHeight = Math.min(...columnHeights)
    const minIndex = columnHeights.indexOf(minHeight)
    columns.value[minIndex].push(note)
    columnHeights[minIndex] += note.height + 20
  })
}

onMounted(() => {
  checkLoginStatus()
  loadNotes()
  window.addEventListener('scroll', handleScroll)
})

onUnmounted(() => {
  window.removeEventListener('scroll', handleScroll)
})
</script>

<style scoped>
/* 全局样式 */
.home-page {
  min-height: 100vh;
  background: linear-gradient(to bottom, #fafafa 0%, #f5f5f5 100%);
}

/* 主内容区 */
.main-content {
  margin-left: 220px;
  padding-top: 64px;
  min-height: 100vh;
}

/* 分类导航 */
.category-nav {
  position: sticky;
  top: 64px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
  padding: 16px 0;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  z-index: 50;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.02);
}

.category-scroll {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 24px;
  display: flex;
  gap: 12px;
  overflow-x: auto;
}

.category-scroll::-webkit-scrollbar {
  display: none;
}

.category-item {
  display: flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 500;
  color: #666;
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 8px 18px;
  border-radius: 20px;
  transition: all 0.3s;
}

.category-item:hover {
  background: #f7f7f7;
  color: #333;
}

.category-item.active {
  background: linear-gradient(135deg, #ff2442 0%, #ff4d6d 100%);
  color: white;
  box-shadow: 0 4px 12px rgba(255, 36, 66, 0.2);
}

/* 瀑布流容器 */
.waterfall-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px;
}

.loading-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: #999;
}

.loading-icon {
  animation: spin 1s linear infinite;
  color: #ff2442;
  margin-bottom: 16px;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.waterfall-columns {
  display: flex;
  gap: 20px;
}

.waterfall-column {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 笔记卡片 */
.note-card {
  background: white;
  border-radius: 16px;
  overflow: hidden;
  cursor: pointer;
  transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.note-card:hover {
  transform: translateY(-8px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.12);
}

.note-image-wrapper {
  position: relative;
  width: 100%;
  overflow: hidden;
  background: #f5f5f5;
}

.note-image {
  width: 100%;
  display: block;
  object-fit: cover;
  transition: transform 0.6s cubic-bezier(0.4, 0, 0.2, 1);
}

.note-card:hover .note-image {
  transform: scale(1.08);
}

.image-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: linear-gradient(to bottom, rgba(0, 0, 0, 0.1) 0%, transparent 30%);
  pointer-events: none;
}

.note-tag {
  position: absolute;
  top: 12px;
  left: 12px;
  display: flex;
  align-items: center;
  gap: 4px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
  padding: 6px 12px;
  border-radius: 16px;
  font-size: 12px;
  font-weight: 600;
  color: #ff2442;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.note-content {
  padding: 14px;
}

.note-title {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  line-height: 1.5;
  margin-bottom: 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.note-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.author-info {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.author-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  object-fit: cover;
  border: 2px solid #f5f5f5;
  flex-shrink: 0;
}

.author-name {
  font-size: 13px;
  color: #666;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.note-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.like-count {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #999;
  transition: color 0.2s;
}

.note-card:hover .like-count {
  color: #ff2442;
}

/* 响应式设计 */
@media (max-width: 1400px) {
  .waterfall-container {
    max-width: 1000px;
  }
}

@media (max-width: 1200px) {
  .waterfall-container {
    gap: 16px;
  }
  
  .waterfall-column:nth-child(4) {
    display: none;
  }
}

@media (max-width: 1024px) {
  .main-content {
    margin-left: 80px;
  }
}

@media (max-width: 900px) {
  .waterfall-column:nth-child(3) {
    display: none;
  }
}

@media (max-width: 768px) {
  .main-content {
    margin-left: 0;
  }
}

@media (max-width: 600px) {
  .waterfall-column:nth-child(2) {
    display: none;
  }
  
  .waterfall-container {
    padding: 16px;
  }
  
  .main-content {
    padding-top: 56px;
  }
  
  .category-nav {
    top: 56px;
  }
}
</style>
