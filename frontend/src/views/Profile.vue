<template>
  <div class="th-page">
    <div class="th-section-title"><h2>个人中心</h2></div>

    <div class="profile-grid">
      <div class="pcard">
        <div class="pcard-title">连续签到</div>
        <div class="pcard-num">{{ signDays }}</div>
        <div class="pcard-hint">天（BitMap 记录）</div>
        <el-button type="primary" @click="handleSign">今日签到</el-button>
      </div>
      <div class="pcard">
        <div class="pcard-title">我的订单</div>
        <div class="pcard-num">{{ orderCount }}</div>
        <div class="pcard-hint">笔</div>
        <el-button @click="$router.push('/orders')">查看订单</el-button>
      </div>
      <div class="pcard">
        <div class="pcard-title">我的关注</div>
        <div class="pcard-num">—</div>
        <div class="pcard-hint">在动态页关注其他观众</div>
        <el-button @click="$router.push('/')">去看动态</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { sign, signCount, myOrders } from '../api'

const signDays = ref(0)
const orderCount = ref(0)

const load = async () => {
  const [cRes, oRes] = await Promise.all([signCount(), myOrders()])
  signDays.value = cRes.data || 0
  orderCount.value = (oRes.data || []).length
}

const handleSign = async () => {
  await sign()
  ElMessage.success('签到成功')
  const res = await signCount()
  signDays.value = res.data || 0
}

onMounted(load)
</script>

<style scoped>
.profile-grid { display: grid; gap: 20px; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); }
.pcard {
  background: #fff; border: 1px solid var(--line); border-radius: var(--radius);
  padding: 26px; text-align: center; box-shadow: var(--shadow);
}
.pcard-title { color: var(--text-mute); font-size: 14px; margin-bottom: 10px; }
.pcard-num { font-family: var(--font-display); font-size: 44px; color: var(--crimson); line-height: 1.1; }
.pcard-hint { color: var(--text-mute); font-size: 12px; margin: 8px 0 18px; }
</style>
