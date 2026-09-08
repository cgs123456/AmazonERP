<template>
  <div class="knowledge-page">
    <!-- hero section - 符合 design-taste-frontend 约束（垂直堆叠，无 eyebrow/split-header） -->
    <div class="hero-section">
      <h1 class="hero-title">知识库管理</h1>
      <p class="hero-subtitle">上传 SOP 文档并即时验证 Agent 检索效果</p>
    </div>

    <AppHeader />
    <AppSidebar />
    <!-- Toast 由 App.vue 全局挂载，页内不再重复挂载（useToast 为单例，重复挂载会叠放两条） -->
    <main class="main-content">
      <!-- 未选店铺提示 -->
      <div v-if="!shopId" class="shop-hint">
        <Icon icon="mdi:store-alert-outline" width="20" />
        <span>请先在顶部选择店铺，知识库数据按店铺隔离</span>
      </div>

      <div v-else class="knowledge-grid">
        <!-- 上传入库卡片 -->
        <section class="card">
          <h2 class="card-title">
            <Icon icon="mdi:file-upload-outline" width="20" />
            文档入库
          </h2>
          <p class="card-desc">支持 PDF / Word / TXT / Markdown / HTML，单文件 ≤10MB</p>
          <div class="upload-row">
            <label class="file-picker">
              <input
                ref="fileInput"
                type="file"
                accept=".pdf,.doc,.docx,.txt,.md,.markdown,.html,.htm"
                :disabled="uploading"
                @change="onFileChange"
              />
              <Icon icon="mdi:paperclip" width="16" />
              <span>{{ selectedFile ? selectedFile.name : '选择文档' }}</span>
            </label>
            <button class="primary-btn" :disabled="!selectedFile || uploading" @click="handleUpload">
              {{ uploading ? '入库中...' : '上传入库' }}
            </button>
          </div>
          <p v-if="uploadError" class="field-error">{{ uploadError }}</p>
        </section>

        <!-- 检索验证卡片 -->
        <section class="card">
          <h2 class="card-title">
            <Icon icon="mdi:database-search-outline" width="20" />
            检索验证
          </h2>
          <p class="card-desc">模拟 Agent 提问，查看混合检索 + 重排命中的片段</p>
          <div class="search-row">
            <input
              v-model="searchQuery"
              class="text-input"
              type="text"
              maxlength="500"
              placeholder="例如：退货政策是怎样的？"
              :disabled="searching"
              @keyup.enter="handleSearch"
            />
            <select v-model.number="topN" class="select-input" :disabled="searching" aria-label="返回条数">
              <option :value="3">3 条</option>
              <option :value="5">5 条</option>
              <option :value="10">10 条</option>
            </select>
            <button class="primary-btn" :disabled="!searchQuery.trim() || searching" @click="handleSearch">
              {{ searching ? '检索中...' : '检索' }}
            </button>
          </div>
          <div v-if="searched && chunks.length === 0" class="empty-state">
            <Icon icon="mdi:database-off-outline" width="40" />
            <p>知识库暂无相关内容</p>
          </div>
          <ul v-else class="chunk-list">
            <li v-for="chunk in chunks" :key="`${chunk.docId}#${chunk.chunkIndex}`" class="chunk-item">
              <div class="chunk-meta">
                <span class="chunk-file">
                  <Icon icon="mdi:file-document-outline" width="14" />
                  {{ chunk.filename }}
                </span>
                <span class="chunk-doc" :title="chunk.docId">docId: {{ shortId(chunk.docId) }}</span>
                <span v-if="chunk.score != null" class="chunk-score">得分 {{ formatScore(chunk.score) }}</span>
              </div>
              <p class="chunk-content">{{ chunk.content }}</p>
            </li>
          </ul>
        </section>

        <!-- 本次上传 + 按 ID 删除卡片 -->
        <section class="card">
          <h2 class="card-title">
            <Icon icon="mdi:file-remove-outline" width="20" />
            文档删除
          </h2>
          <p class="card-desc">删除整篇文档及其全部文本块（需归属当前店铺）</p>
          <div class="search-row">
            <input
              v-model="deleteDocId"
              class="text-input"
              type="text"
              placeholder="输入 docId（上传成功后会显示）"
              :disabled="deleting"
              @keyup.enter="handleDelete"
            />
            <button class="danger-btn" :disabled="!deleteDocId.trim() || deleting" @click="handleDelete">
              {{ deleting ? '删除中...' : '删除' }}
            </button>
          </div>
          <ul v-if="sessionDocs.length > 0" class="session-list">
            <li v-for="doc in sessionDocs" :key="doc.docId" class="session-item">
              <span class="session-file">{{ doc.filename }}</span>
              <span class="chunk-doc" :title="doc.docId">{{ shortId(doc.docId) }} · {{ doc.chunks }} 块</span>
              <button class="link-btn" @click="fillDeleteDocId(doc.docId)">填入</button>
            </li>
          </ul>
        </section>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import { useToast } from '../composables/useToast'
import { useShopGuard } from '../composables/useShopGuard'
import {
  uploadKnowledgeDocument,
  deleteKnowledgeDocument,
  searchKnowledgeBase,
  type KnowledgeChunk,
  type KnowledgeUploadResult
} from '../api/knowledge'

const { showToast } = useToast()

// B4 公共守卫（别名保持模板 shopId 不变）
const { currentShopId: shopId, refreshShop } = useShopGuard()

// —— 上传 ——
const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const uploading = ref(false)
const uploadError = ref('')
const sessionDocs = ref<KnowledgeUploadResult[]>([])

const MAX_BYTES = 10 * 1024 * 1024

const onFileChange = () => {
  uploadError.value = ''
  const file = fileInput.value?.files?.[0] ?? null
  if (!file) {
    selectedFile.value = null
    return
  }
  if (file.size > MAX_BYTES) {
    uploadError.value = '文件大小不能超过10MB'
    selectedFile.value = null
    return
  }
  selectedFile.value = file
}

const handleUpload = async () => {
  const sid = refreshShop()
  if (!selectedFile.value || !sid) return
  uploading.value = true
  uploadError.value = ''
  try {
    const res = await uploadKnowledgeDocument(sid, selectedFile.value)
    if (res.code === 200 && res.data) {
      sessionDocs.value.unshift(res.data)
      deleteDocId.value = res.data.docId
      showToast(`入库成功：${res.data.filename}（${res.data.indexed} 块）`, 'success')
      selectedFile.value = null
      if (fileInput.value) fileInput.value.value = ''
    } else {
      showToast(res.message || '上传失败', 'error')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '上传失败', 'error')
  } finally {
    uploading.value = false
  }
}

// —— 检索验证 ——
const searchQuery = ref('')
const topN = ref(5)
const searching = ref(false)
const searched = ref(false)
const chunks = ref<KnowledgeChunk[]>([])

const handleSearch = async () => {
  const query = searchQuery.value.trim()
  const sid = refreshShop()
  if (!query || !sid) return
  searching.value = true
  try {
    const res = await searchKnowledgeBase(sid, query, topN.value)
    if (res.code === 200 && Array.isArray(res.data)) {
      chunks.value = res.data
      searched.value = true
    } else {
      showToast(res.message || '检索失败', 'error')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '检索失败', 'error')
  } finally {
    searching.value = false
  }
}

// —— 删除 ——
const deleteDocId = ref('')
const deleting = ref(false)

const fillDeleteDocId = (docId: string) => {
  deleteDocId.value = docId
}

const handleDelete = async () => {
  const docId = deleteDocId.value.trim()
  const sid = refreshShop()
  if (!docId || !sid) return
  deleting.value = true
  try {
    const res = await deleteKnowledgeDocument(sid, docId)
    if (res.code === 200 && res.data) {
      sessionDocs.value = sessionDocs.value.filter(d => d.docId !== res.data.docId)
      // 已展示的检索结果中属于该文档的块一并移除
      chunks.value = chunks.value.filter(c => c.docId !== res.data.docId)
      deleteDocId.value = ''
      showToast(`已删除 ${res.data.deleted} 个文本块`, 'success')
    } else {
      showToast(res.message || '删除失败', 'error')
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败', 'error')
  } finally {
    deleting.value = false
  }
}

// —— 展示辅助 ——
const shortId = (docId: string) => (docId.length > 12 ? `${docId.slice(0, 12)}…` : docId)
const formatScore = (score: number) => score.toFixed(2)
</script>

<style scoped>
.knowledge-page {
  min-height: 100dvh;
  background: var(--color-background);
}

.hero-section {
  padding: var(--spacing-2xl) var(--spacing-xl) var(--spacing-lg);
  text-align: center;
}

.hero-title {
  font-size: var(--font-size-8);
  font-weight: 700;
  color: var(--color-text);
  margin: 0 0 var(--spacing-sm);
}

.hero-subtitle {
  font-size: var(--font-size-3);
  color: var(--color-muted);
  margin: 0;
}

.main-content {
  margin-left: 220px;
  padding: var(--spacing-lg) var(--spacing-xl) var(--spacing-2xl);
  max-width: 960px;
}

.shop-hint {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-muted);
  font-size: var(--font-size-2);
}

.knowledge-grid {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-sm);
  padding: var(--spacing-lg);
}

.card-title {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-4);
  font-weight: 600;
  color: var(--color-text);
  margin: 0 0 var(--spacing-xs);
}

.card-desc {
  font-size: var(--font-size-2);
  color: var(--color-muted);
  margin: 0 0 var(--spacing-md);
}

.upload-row,
.search-row {
  display: flex;
  gap: var(--spacing-sm);
  align-items: center;
  flex-wrap: wrap;
}

.file-picker {
  display: inline-flex;
  align-items: center;
  gap: var(--spacing-xs);
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-2);
  color: var(--color-muted);
  cursor: pointer;
  max-width: 100%;
}

.file-picker input[type='file'] {
  display: none;
}

.file-picker span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
}

.text-input {
  flex: 1;
  min-width: 200px;
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-2);
  color: var(--color-text);
  background: var(--color-surface);
}

.select-input {
  padding: var(--spacing-sm) var(--spacing-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--font-size-2);
  color: var(--color-text);
  background: var(--color-surface);
}

.primary-btn {
  padding: var(--spacing-sm) var(--spacing-lg);
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-primary);
  color: #fff;
  font-size: var(--font-size-2);
  font-weight: 600;
  cursor: pointer;
}

.primary-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.danger-btn {
  padding: var(--spacing-sm) var(--spacing-lg);
  border: 1px solid var(--color-error);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-error);
  font-size: var(--font-size-2);
  font-weight: 600;
  cursor: pointer;
}

.danger-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.link-btn {
  border: none;
  background: transparent;
  color: var(--color-primary);
  font-size: var(--font-size-2);
  cursor: pointer;
  padding: 0;
}

.field-error {
  color: var(--color-error);
  font-size: var(--font-size-2);
  margin: var(--spacing-sm) 0 0;
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xl) 0 var(--spacing-sm);
  color: var(--color-muted);
  font-size: var(--font-size-2);
}

.chunk-list,
.session-list {
  list-style: none;
  margin: var(--spacing-md) 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.chunk-item {
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--spacing-md);
}

.chunk-meta {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  flex-wrap: wrap;
  margin-bottom: var(--spacing-xs);
}

.chunk-file {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--font-size-2);
  font-weight: 600;
  color: var(--color-text);
}

.chunk-doc {
  font-family: var(--font-mono);
  font-size: var(--font-size-1);
  color: var(--color-muted);
}

.chunk-score {
  font-size: var(--font-size-1);
  color: var(--color-primary);
  background: var(--color-primary-light);
  border-radius: var(--radius-sm);
  padding: 2px 8px;
}

.chunk-content {
  font-size: var(--font-size-2);
  color: var(--color-text);
  line-height: 1.6;
  margin: 0;
  white-space: pre-wrap;
}

.session-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  font-size: var(--font-size-2);
  padding: var(--spacing-sm) 0;
  border-top: 1px solid var(--color-border);
}

.session-file {
  font-weight: 600;
  color: var(--color-text);
}

@media (max-width: 768px) {
  .main-content {
    margin-left: 0;
    padding: var(--spacing-md);
  }
}
</style>
