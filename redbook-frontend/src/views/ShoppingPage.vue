<template>
  <div class="shopping-page">
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
            :key="category.id"
            class="category-item" 
            :class="{ active: activeCategory === category.id }"
            @click="activeCategory = category.id"
          >
            <Icon v-if="category.icon" :icon="category.icon" width="16" />
            {{ category.name }}
          </button>
        </div>
      </nav>

      <!-- 商品网格 -->
      <div class="products-container">
        <div v-if="loading" class="loading-container">
          <Icon icon="mdi:loading" class="loading-icon" width="48" />
          <p>加载中...</p>
        </div>
        <div v-else-if="products.length === 0" class="empty-container">
          <Icon icon="mdi:package-variant" width="64" />
          <p>暂无商品</p>
        </div>
        <div v-else class="products-grid">
          <div 
            v-for="product in products" 
            :key="product.id"
            class="product-card"
            @click="viewProduct(product.id)"
          >
            <div class="product-image">
              <img :src="product.image" :alt="product.name" loading="lazy" />
              <div class="product-tag" v-if="getProductTag(product)">{{ getProductTag(product) }}</div>
            </div>
            <div class="product-info">
              <h3 class="product-title">{{ product.name }}</h3>
              <p class="product-desc">{{ product.description }}</p>
              <div class="product-brand" v-if="product.brand">{{ product.brand }}</div>
              <div class="product-footer">
                <div class="product-price">
                  <span class="price-symbol">¥</span>
                  <span class="price-value">{{ product.price }}</span>
                </div>
                <div class="product-stats">
                  <span class="stat-item">
                    <Icon icon="mdi:cart-outline" width="14" />
                    {{ formatNumber(product.sales) }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </main>

    <!-- 商品详情弹窗 -->
    <ProductDetailModal 
      :visible="showProductModal"
      :product-id="selectedProductId"
      @update:visible="showProductModal = $event"
      @add-to-cart="handleAddToCart"
      @buy-now="handleBuyNow"
    />

    <!-- 购买弹窗 -->
    <BuyModal
      :visible="showBuyModal"
      :product="buyProduct"
      :attributes="buyAttributes"
      @update:visible="showBuyModal = $event"
      @success="handleBuySuccess"
    />

    <!-- 购物助手浮动按钮 -->
    <button class="assistant-fab" @click="showAssistant = true">
      <Icon icon="mdi:robot" width="28" />
      <span class="fab-text">购物助手</span>
    </button>

    <!-- 购物助手聊天弹窗 -->
    <ShoppingAssistant
      :visible="showAssistant"
      @update:visible="showAssistant = $event"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { Icon } from '@iconify/vue'
import AppHeader from '../components/AppHeader.vue'
import AppSidebar from '../components/AppSidebar.vue'
import ProductDetailModal from '../components/ProductDetailModal.vue'
import BuyModal from '../components/BuyModal.vue'
import ShoppingAssistant from '../components/ShoppingAssistant.vue'
import { getProductList, addToCart, type Product, type CustomAttribute } from '../api/product'
import { useToast } from '../composables/useToast'

const { showToast } = useToast()

// 分类数据
const categories = ref([
  { id: 'all', name: '全部', icon: 'mdi:fire' },
  { id: 'beauty', name: '美妆护肤', icon: '' },
  { id: 'fashion', name: '服饰鞋包', icon: '' },
  { id: 'digital', name: '数码家电', icon: '' },
  { id: 'food', name: '食品饮料', icon: '' },
  { id: 'home', name: '家居生活', icon: '' },
  { id: 'sports', name: '运动户外', icon: '' },
  { id: 'books', name: '图书文娱', icon: '' },
])

const activeCategory = ref('all')
const allProducts = ref<Product[]>([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(20)
const hasMore = ref(true)

// 商品详情弹窗
const showProductModal = ref(false)
const selectedProductId = ref<number | null>(null)

// 购买弹窗
const showBuyModal = ref(false)

const buyProduct = ref<Product | null>(null)
const buyAttributes = ref<CustomAttribute[] | null>(null)

// 购物助手
const showAssistant = ref(false)

// 根据分类过滤商品
const products = computed(() => {
  if (activeCategory.value === 'all') {
    return allProducts.value
  }
  return allProducts.value.filter(product => product.type === activeCategory.value)
})

// 加载商品列表
const loadProducts = async () => {
  if (loading.value || !hasMore.value) return
  
  try {
    loading.value = true
    const response = await getProductList(currentPage.value, pageSize.value)
    if (response.code === 200 && response.data) {
      allProducts.value = [...allProducts.value, ...response.data]
      
      // 如果返回的数据少于每页数量，说明没有更多数据了
      if (response.data.length < pageSize.value) {
        hasMore.value = false
      }
    } else {
      showToast(response.message || '加载商品失败', 'error')
    }
  } catch (error: unknown) {
    const errorMsg = error instanceof Error ? error.message : '网络错误，请稍后重试'
    showToast(errorMsg, 'error')
  } finally {
    loading.value = false
  }
}

// 格式化数字
const formatNumber = (num: number) => {
  if (num >= 10000) {
    return (num / 10000).toFixed(1) + 'w'
  }
  return num.toString()
}

// 查看商品详情
const viewProduct = (id: number) => {
  selectedProductId.value = id
  showProductModal.value = true
}

// 获取商品标签
const getProductTag = (product: Product) => {
  if (product.sales > 1000) return '热卖'
  if (product.stock < 10) return '库存紧张'
  return ''
}

// 加入购物车
const handleAddToCart = async (data: { productId: number }) => {
  try {
    const response = await addToCart(data.productId, 1)
    if (response.code === 200) {
      showToast(`已将商品加入购物车`, 'success')
      showProductModal.value = false
    } else {
      showToast(response.message || '加入购物车失败', 'error')
    }
  } catch (error: unknown) {
    const errorMsg = error instanceof Error ? error.message : '加入购物车失败'
    showToast(errorMsg, 'error')
  }
}

// 立即购买 - 打开购买弹窗
const handleBuyNow = (data: { product: Product; attributes: CustomAttribute[] | null }) => {
  buyProduct.value = data.product
  buyAttributes.value = data.attributes
  showBuyModal.value = true
}

// 购买成功
const handleBuySuccess = () => {
  showProductModal.value = false
}

onMounted(() => {
  loadProducts()
})
</script>

<style scoped>
.shopping-page {
  min-height: 100vh;
  background: #f8f8f8;
}

.main-content {
  margin-left: 220px;
  margin-top: 64px;
  padding: 0;
  min-height: calc(100vh - 64px);
}

/* 分类导航 */
.category-nav {
  position: sticky;
  top: 64px;
  background: white;
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
  z-index: 90;
  padding: 0 24px;
}

.category-scroll {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 16px 0;
  scrollbar-width: none;
}

.category-scroll::-webkit-scrollbar {
  display: none;
}

.category-item {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 16px;
  border: none;
  background: #f5f5f5;
  border-radius: 20px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}

.category-item:hover {
  background: #ebebeb;
  color: #333;
}

.category-item.active {
  background: #ff2442;
  color: white;
  font-weight: 500;
}

/* 商品容器 */
.products-container {
  padding: 24px;
}

.products-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 20px;
  max-width: 1400px;
  margin: 0 auto;
}

/* 商品卡片 */
.product-card {
  background: white;
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: all 0.3s;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}

.product-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}

.product-image {
  position: relative;
  width: 100%;
  padding-top: 133%;
  overflow: hidden;
  background: #f5f5f5;
}

.product-image img {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.3s;
}

.product-card:hover .product-image img {
  transform: scale(1.05);
}

.product-tag {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 4px 12px;
  background: rgba(255, 36, 66, 0.9);
  color: white;
  font-size: 12px;
  border-radius: 12px;
  font-weight: 500;
}

.product-info {
  padding: 12px;
}

.product-title {
  font-size: 14px;
  font-weight: 500;
  color: #333;
  margin: 0 0 6px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-desc {
  font-size: 12px;
  color: #999;
  margin: 0 0 8px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-brand {
  font-size: 11px;
  color: #666;
  background: #f5f5f5;
  padding: 2px 8px;
  border-radius: 4px;
  display: inline-block;
  margin-bottom: 8px;
}

.product-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.product-price {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.price-symbol {
  font-size: 12px;
  color: #ff2442;
  font-weight: 600;
}

.price-value {
  font-size: 18px;
  color: #ff2442;
  font-weight: 700;
}

.price-original {
  font-size: 12px;
  color: #999;
  text-decoration: line-through;
}

.product-stats {
  display: flex;
  gap: 12px;
}

.stat-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #999;
}

/* 加载和空状态 */
.loading-container,
.empty-container {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: #999;
}

.loading-icon {
  animation: spin 1s linear infinite;
  color: #ff2442;
}

@keyframes spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}

.empty-container {
  color: #ccc;
}

.empty-container p,
.loading-container p {
  margin-top: 16px;
  font-size: 14px;
}

/* 购物助手浮动按钮 */
.assistant-fab {
  position: fixed;
  bottom: 24px;
  right: 24px;
  width: 64px;
  height: 64px;
  border: none;
  background: linear-gradient(135deg, #ff2442 0%, #ff4757 100%);
  color: white;
  border-radius: 50%;
  box-shadow: 0 4px 16px rgba(255, 36, 66, 0.4);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  transition: all 0.3s;
  z-index: 999;
}

.assistant-fab:hover {
  transform: scale(1.1);
  box-shadow: 0 6px 24px rgba(255, 36, 66, 0.5);
}

.fab-text {
  font-size: 10px;
  font-weight: 500;
}

/* 响应式 */
@media (max-width: 1024px) {
  .main-content {
    margin-left: 80px;
  }
  
  .products-grid {
    grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
    gap: 16px;
  }
}

@media (max-width: 768px) {
  .main-content {
    margin-left: 0;
  }
  
  .category-nav {
    padding: 0 16px;
  }
  
  .products-container {
    padding: 16px;
  }
  
  .products-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 12px;
  }
}

@media (max-width: 600px) {
  .main-content {
    margin-top: 56px;
  }
  
  .category-nav {
    top: 56px;
  }

  .assistant-fab {
    bottom: 16px;
    right: 16px;
    width: 56px;
    height: 56px;
  }

  .fab-text {
    font-size: 9px;
  }
}
</style>
