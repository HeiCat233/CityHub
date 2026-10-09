<template>
  <div v-if="show" class="th-page">
    <div class="detail-head">
      <div class="poster">
        <img v-if="show.images" :src="show.images.split(',')[0]" @error="onImgError" />
        <div class="fallback">{{ show.name.slice(0, 2) }}</div>
      </div>
      <div class="info">
        <span class="th-badge" style="position: static; display:inline-block; margin-bottom:12px;">
          {{ statusText(show.status) }}
        </span>
        <h1>{{ show.name }}</h1>
        <div class="meta-line">🗓 演出时间：{{ formatDate(show.beginTime) }}</div>
        <div class="meta-line">🎫 开票时间：{{ formatDate(show.saleBeginTime) }}</div>
        <div class="meta-line">📍 {{ show.city }} · {{ show.venueName }}</div>
        <div class="meta-line">⏱ 时长约 {{ show.duration }} 分钟 · 评分 {{ (show.score / 10).toFixed(1) }}</div>
        <p class="desc">{{ show.description }}</p>
      </div>
    </div>

    <div class="th-section-title">
      <h2>选择票档</h2>
      <span class="hint">一人一单，抢到后 15 分钟内支付</span>
    </div>

    <div v-if="tiers.length === 0" class="th-empty">暂无票档</div>
    <div v-for="t in tiers" :key="t.id" class="th-tier" :class="{ 'sold-out': t.stock <= 0 }">
      <div>
        <div class="th-tier-name">{{ t.name }}</div>
        <div class="th-tier-stock">
          剩余 <b>{{ t.stock ?? '-' }}</b> 张 · {{ t.rules }}
        </div>
      </div>
      <div style="display:flex; align-items:center; gap:20px;">
        <div class="th-tier-price">¥{{ (t.price / 100).toFixed(0) }}</div>
        <el-button
          type="primary"
          :disabled="t.stock <= 0 || !isOnSale(t)"
          @click="goTicket(t)"
        >{{ buttonText(t) }}</el-button>
      </div>
    </div>

    <div class="th-section-title"><h2>演出介绍</h2></div>
    <div class="desc-block">{{ show.description || '暂无介绍' }}</div>
  </div>
  <div v-else class="th-empty" style="padding-top:120px;">加载中…</div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getShow, listTiers } from '../api'

const route = useRoute()
const router = useRouter()
const show = ref(null)
const tiers = ref([])

const load = async () => {
  const id = route.params.id
  const [sRes, tRes] = await Promise.all([getShow(id), listTiers(id)])
  show.value = sRes.data
  tiers.value = tRes.data || []
}

const isOnSale = (t) => {
  if (!t.beginTime) return true
  return new Date(String(t.beginTime).replace(' ', 'T')) <= new Date()
}

const buttonText = (t) => {
  if (t.stock <= 0) return '已售罄'
  if (!isOnSale(t)) return '未开票'
  return '立即抢票'
}

const goTicket = (t) => {
  if (!localStorage.getItem('token')) {
    router.push({ path: '/login', query: { redirect: `/ticket/${t.id}` } })
    return
  }
  router.push(`/ticket/${t.id}`)
}

const statusText = (s) => ({ 1: '待开票', 2: '售票中', 3: '已售罄', 4: '已结束' }[s] || '售票中')
const formatDate = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '-')
const onImgError = (e) => { e.target.style.display = 'none' }

onMounted(load)
</script>

<style scoped>
.detail-head { display: grid; grid-template-columns: 300px 1fr; gap: 32px; align-items: start; }
.poster {
  position: relative; aspect-ratio: 3/4; border-radius: var(--radius);
  overflow: hidden; background: linear-gradient(150deg, #232838, #101219);
  box-shadow: var(--shadow);
}
.poster img { width: 100%; height: 100%; object-fit: cover; }
.poster .fallback {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  font-family: var(--font-display); font-size: 52px; color: rgba(217,164,65,.5);
}
.info h1 { font-size: 30px; margin: 0 0 18px; line-height: 1.4; }
.meta-line { color: var(--text-mute); font-size: 15px; margin-bottom: 8px; }
.desc { color: #4b5563; line-height: 1.95; margin-top: 18px; }
.desc-block {
  background: #fff; border: 1px solid var(--line); border-radius: var(--radius);
  padding: 24px; line-height: 2; color: #4b5563;
}
@media (max-width: 720px) {
  .detail-head { grid-template-columns: 1fr; }
  .poster { max-width: 240px; }
  .info h1 { font-size: 22px; }
}
</style>
