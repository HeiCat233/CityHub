<template>
  <div class="th-page">
    <div class="th-section-title">
      <h2>抢票</h2>
      <span class="hint">Redis + Lua 原子校验 · MQ 异步出票 · 绝不超卖</span>
    </div>

    <div v-if="tier" class="ticket-box">
      <div class="left">
        <h3>{{ tier.name }}</h3>
        <div class="price">¥{{ (tier.price / 100).toFixed(0) }}</div>
        <ul class="rules">
          <li>一人一单，重复抢票会被拦截</li>
          <li>抢票成功后请在订单页完成支付</li>
          <li>库存实时来自 Redis，无需刷新</li>
        </ul>
      </div>

      <div class="right">
        <div v-if="!onSale" class="cd-wrap">
          <div class="cd-label">距离开票</div>
          <div class="th-countdown">
            <div class="cell"><b>{{ cd.days }}</b><i>天</i></div>
            <div class="cell"><b>{{ cd.hours }}</b><i>时</i></div>
            <div class="cell"><b>{{ cd.minutes }}</b><i>分</i></div>
            <div class="cell"><b>{{ cd.seconds }}</b><i>秒</i></div>
          </div>
        </div>

        <div class="stock-wrap">
          <div class="stock-num">{{ stock }}</div>
          <div class="stock-label">当前余票</div>
        </div>

        <el-button
          class="grab-btn"
          type="primary"
          size="large"
          :loading="loading"
          :disabled="!onSale || stock <= 0"
          @click="doSeckill"
        >{{ stock <= 0 ? '已售罄' : (onSale ? '立即抢票' : '未开票') }}</el-button>

        <div v-if="lastOrderId" class="result">
          🎉 抢票成功！订单号 <b>{{ lastOrderId }}</b>
          <el-button link type="primary" @click="$router.push('/orders')">去支付 →</el-button>
        </div>
      </div>
    </div>
    <div v-else class="th-empty">加载中…</div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listTiers, seckill } from '../api'

const route = useRoute()
const tier = ref(null)
const stock = ref(0)
const loading = ref(false)
const lastOrderId = ref(null)
const cd = reactive({ days: '00', hours: '00', minutes: '00', seconds: '00' })
let timer = null

const onSale = computed(() => {
  if (!tier.value?.beginTime) return true
  return new Date(String(tier.value.beginTime).replace(' ', 'T')) <= new Date()
})

// 票档详情需要按 showId 查，这里用 tierId 反查（后端按演出返回列表）
const load = async () => {
  const tierId = Number(route.params.tierId)
  // 遍历演出找票档：演示项目数据量小，直接按已知映射查询
  const res = await listTiers(guessShowId(tierId))
  const list = res.data || []
  tier.value = list.find((t) => t.id === tierId) || list[0]
  stock.value = tier.value?.stock ?? 0
}

// 演示数据中票档 id 与 showId 的映射（1-3 属于演出1，4-5 演出2，6-7 演出3…）
const guessShowId = (tierId) => {
  if (tierId <= 3) return 1
  if (tierId <= 5) return 2
  if (tierId <= 7) return 3
  if (tierId <= 9) return 4
  if (tierId <= 11) return 5
  return 6
}

const tick = () => {
  if (!tier.value?.beginTime) return
  const target = new Date(String(tier.value.beginTime).replace(' ', 'T')).getTime()
  const diff = target - Date.now()
  if (diff <= 0) {
    Object.assign(cd, { days: '00', hours: '00', minutes: '00', seconds: '00' })
    return
  }
  const s = Math.floor(diff / 1000)
  cd.days = String(Math.floor(s / 86400)).padStart(2, '0')
  cd.hours = String(Math.floor((s % 86400) / 3600)).padStart(2, '0')
  cd.minutes = String(Math.floor((s % 3600) / 60)).padStart(2, '0')
  cd.seconds = String(s % 60).padStart(2, '0')
}

const doSeckill = async () => {
  loading.value = true
  try {
    const res = await seckill(tier.value.id)
    lastOrderId.value = res.data
    ElMessage.success('抢票成功，请尽快支付')
    // 抢到后库存 -1，本地立即反馈
    stock.value = Math.max(0, stock.value - 1)
  } catch (e) {
    // 库存不足/重复下单已由拦截器提示
  } finally {
    loading.value = false
  }
}

// 轮询余票，模拟实时库存变化
const pollStock = async () => {
  try {
    const res = await listTiers(guessShowId(Number(route.params.tierId)))
    const t = (res.data || []).find((x) => x.id === Number(route.params.tierId))
    if (t) stock.value = t.stock
  } catch (e) { /* 忽略 */ }
}

onMounted(async () => {
  await load()
  tick()
  timer = setInterval(() => { tick(); pollStock() }, 1000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.ticket-box {
  display: grid; grid-template-columns: 1fr 380px; gap: 40px;
  background: #fff; border: 1px solid var(--line); border-radius: var(--radius);
  padding: 36px; box-shadow: var(--shadow);
}
.left h3 { font-family: var(--font-display); font-size: 26px; margin: 0 0 14px; }
.price { color: var(--crimson); font-size: 34px; font-weight: 700; margin-bottom: 20px; }
.rules { color: var(--text-mute); line-height: 2.1; padding-left: 18px; margin: 0; font-size: 14px; }
.right { display: flex; flex-direction: column; align-items: center; gap: 22px; justify-content: center; }
.cd-label { text-align: center; color: var(--text-mute); margin-bottom: 10px; font-size: 14px; }
.stock-wrap { text-align: center; }
.stock-num { font-family: var(--font-display); font-size: 52px; color: var(--ink); line-height: 1; }
.stock-label { color: var(--text-mute); font-size: 13px; margin-top: 6px; }
.grab-btn { width: 100%; height: 52px; font-size: 17px; }
.result { font-size: 14px; color: #2f6f4f; text-align: center; line-height: 1.9; }
@media (max-width: 720px) {
  .ticket-box { grid-template-columns: 1fr; padding: 22px; }
}
</style>
