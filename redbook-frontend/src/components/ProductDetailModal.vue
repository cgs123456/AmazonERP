<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="visible" class="modal-overlay" @click="handleClose">
        <div class="modal-container" @click.stop>
          <div v-if="loading" class="loading-state">
            <Icon icon="mdi:loading" class="loading-icon" width="48" />
            <p>加载中...</p>
          </div>

          <div v-else-if="productDetail" class="modal-content">
            <!-- 左侧：商品图片 -->
            <div class="product-image-section">
              <img :src="productDetail.product.image" :alt="productDetail.product.name" />
            </div>

            <!-- 右侧：商品信息 -->
            <div class="product-info-section">
              <h2 class="product-name">{{ productDetail.product.name }}</h2>
              <p class="product-description">{{ productDetail.product.description }}</p>

              <div class="product-price-box">
                <div class="price-main">
                  <span class="price-symbol">¥</span>
                  <span class="price-value">{{ productDetail.product.price }}</span>
                </div>
                <div class="product-meta">
                  <span class="meta-item">销量: {{ productDetail.product.sales }}</span>
                  <span class="meta-item">库存: {{ productDetail.product.stock }}</span>
                </div>
              </div>

              <!-- 品牌信息 -->
              <div class="product-detail-item" v-if="productDetail.product.brand">
                <span class="detail-label">品牌</span>
                <span class="detail-value">{{ productDetail.product.brand }}</span>
              </div>

              <!-- 商品类型 -->
              <div class="product-detail-item" v-if="productDetail.product.type">
                <span class="detail-label">分类</span>
                <span class="detail-value">{{ productDetail.product.type }}</span>
              </div>

              <!-- 店铺信息 -->
              <div class="shop-info" v-if="productDetail.shop">
                <div class="shop-header">
                  <Icon icon="mdi:store" width="20" />
                  <span class="shop-name">{{ productDetail.shop.name || '店铺' }}</span>
                </div>
              </div>

              <!-- 操作按钮 -->
              <div class="action-buttons">
                <button class="btn btn-cart" @click="handleAddToCart">
                  <Icon icon="mdi:cart-outline" width="20" />
                  加入购物车
                </button>
                <button class="btn btn-buy" @click="handleBuyNow">
                  <Icon icon="mdi:lightning-bolt" width="20" />
                  立即购买
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import { getProduct, type ProductVo, type Product, type CustomAttribute } from '../api/product'

interface Props {
  visible: boolean
  productId: number | null
}

interface Emits {
  (e: 'update:visible', value: boolean): void
  (e: 'addToCart', data: { productId: number }): void
  (e: 'buyNow', data: { product: Product; attributes: CustomAttribute[] | null }): void
}

const props = defineProps<Props>()
const emit = defineEmits<Emits>()

const loading = ref(false)
const productDetail = ref<ProductVo | null>(null)

// 监听弹窗打开和 productId 变化
watch([() => props.visible, () => props.productId], async ([newVisible, newId]) => {
  if (newVisible && newId) {
    await loadProductDetail(newId)
  }
})

// 加载商品详情
const loadProductDetail = async (productId: number) => {
  try {
    loading.value = true
    const response = await getProduct(productId)
    if (response.code === 200 && response.data) {
      productDetail.value = response.data
    }
  } catch (error) {
    // 加载商品详情失败
  } finally {
    loading.value = false
  }
}

// 关闭弹窗
const handleClose = () => {
  emit('update:visible', false)
  setTimeout(() => {
    productDetail.value = null
  }, 300)
}

// 加入购物车
const handleAddToCart = () => {
  if (!productDetail.value) return
  emit('addToCart', {
    productId: productDetail.value.product.id
  })
}

// 立即购买 - 打开购买弹窗
const handleBuyNow = () => {
  if (!productDetail.value) return
  emit('buyNow', {
    product: productDetail.value.product,
    attributes: productDetail.value.customAttributes || null
  })
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
  z-index: 9999;
  padding: 20px;
}

.modal-container {
  position: relative;
  background: white;
  border-radius: 16px;
  max-width: 900px;
  width: 100%;
  max-height: 90vh;
  overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}

.loading-state {
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
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.modal-content {
  display: flex;
  gap: 32px;
  padding: 32px;
}

/* 左侧图片 */
.product-image-section {
  flex: 0 0 400px;
  height: 500px;
  border-radius: 12px;
  overflow: hidden;
  background: #f5f5f5;
}

.product-image-section img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 右侧信息 */
.product-info-section {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.product-name {
  font-size: 24px;
  font-weight: 600;
  color: #333;
  margin: 0 0 12px 0;
  line-height: 1.4;
}

.product-description {
  font-size: 14px;
  color: #666;
  margin: 0 0 24px 0;
  line-height: 1.6;
}

.product-price-box {
  background: linear-gradient(135deg, #fff5f5 0%, #ffe8e8 100%);
  padding: 20px;
  border-radius: 12px;
  margin-bottom: 24px;
}

.price-main {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-bottom: 8px;
}

.price-symbol {
  font-size: 18px;
  color: #ff2442;
  font-weight: 600;
}

.price-value {
  font-size: 32px;
  color: #ff2442;
  font-weight: 700;
}

.product-meta {
  display: flex;
  gap: 16px;
}

.meta-item {
  font-size: 13px;
  color: #666;
}

.product-detail-item {
  display: flex;
  align-items: center;
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}

.detail-label {
  width: 80px;
  font-size: 14px;
  color: #999;
}

.detail-value {
  flex: 1;
  font-size: 14px;
  color: #333;
}

.shop-info {
  padding: 16px;
  background: #f8f8f8;
  border-radius: 8px;
  margin: 16px 0;
}

.shop-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.shop-name {
  font-size: 14px;
  font-weight: 500;
  color: #333;
}

.action-buttons {
  display: flex;
  gap: 12px;
  margin-top: auto;
  padding-top: 24px;
}

.btn {
  flex: 1;
  height: 48px;
  border: none;
  border-radius: 24px;
  font-size: 16px;
  font-weight: 500;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: all 0.3s;
}

.btn-cart {
  background: white;
  color: #ff2442;
  border: 2px solid #ff2442;
}

.btn-cart:hover {
  background: #fff5f5;
}

.btn-buy {
  background: linear-gradient(135deg, #ff2442 0%, #ff4757 100%);
  color: white;
}

.btn-buy:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(255, 36, 66, 0.3);
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
@media (max-width: 768px) {
  .modal-content {
    flex-direction: column;
    padding: 20px;
  }

  .product-image-section {
    flex: none;
    width: 100%;
    height: 300px;
  }

  .product-name {
    font-size: 20px;
  }

  .price-value {
    font-size: 28px;
  }

  .action-buttons {
    flex-direction: column;
  }
}
</style>
