<template>
  <div class="th-page">
    <div class="th-section-title">
      <h2>我的订单</h2>
      <span class="hint">抢到票后请在 15 分钟内完成支付</span>
    </div>

    <div v-if="orders.length === 0" class="th-empty">
      <div class="big">还没有订单</div>
      <el-button type="primary" @click="$router.push('/')">去看演出</el-button>
    </div>

    <div v-for="o in orders" :key="o.id" class="order-row">
      <div class="order-main">
        <div class="order-title">订单号 {{ o.id }}</div>
        <div class="order-meta">
          演出 #{{ o.showId }} · 票档 #{{ o.tierId }} · 数量 {{ o.quantity }}
        </div>
        <div class="order-meta">下单时间 {{ formatDate(o.createTime) }}</div>
      </div>
      <div class="order-right">
        <div class="order-price">¥{{ ((o.price || 0) / 100).toFixed(0) }}</div>
        <el-tag :type="statusType(o.status)" effect="light">{{ statusText(o.status) }}</el-tag>
        <el-button
          v-if="o.status === 1"
          type="primary"
          size="small"
          @click="handlePay(o)"
        >立即支付</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { myOrders, payOrder } from '../api'

const orders = ref([])

const load = async () => {
  const res = await myOrders()
  orders.value = res.data || []
}

const handlePay = async (o) => {
  await payOrder(o.id)
  ElMessage.success('支付成功')
  load()
}

const statusText = (s) => ({
  1: '待支付', 2: '已支付', 3: '已出票', 4: '已取消', 5: '退款中', 6: '已退款'
}[s] || '未知')
const statusType = (s) => ({ 1: 'warning', 2: 'success', 3: 'success', 4: 'info' }[s] || 'info')
const formatDate = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '-')

onMounted(load)
</script>

<style scoped>
.order-row {
  display: flex; align-items: center; justify-content: space-between; gap: 20px;
  background: #fff; border: 1px solid var(--line); border-radius: 12px;
  padding: 20px 24px; margin-bottom: 12px; box-shadow: 0 4px 14px rgba(13,15,20,.05);
}
.order-title { font-family: var(--font-display); font-size: 16px; margin-bottom: 8px; }
.order-meta { color: var(--text-mute); font-size: 13px; margin-bottom: 4px; }
.order-right { display: flex; align-items: center; gap: 14px; }
.order-price { color: var(--crimson); font-size: 20px; font-weight: 700; }
@media (max-width: 720px) {
  .order-row { flex-direction: column; align-items: flex-start; }
}
</style>
