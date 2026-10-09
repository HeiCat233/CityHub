<template>
  <div>
    <section class="th-hero">
      <h1>下一场，去现场<br />把耳机里的声音换成万人合唱</h1>
      <p>演唱会 / 音乐节 / 话剧 / 交响乐 / 体育赛事 —— 一站式开票抢票，实时余票、一人一单、绝不超卖。</p>
    </section>

    <div class="th-page">
      <div class="th-section-title">
        <h2>演出分类</h2>
        <span class="hint">点击筛选</span>
      </div>
      <div class="cat-row">
        <button
          v-for="c in categories"
          :key="c.id"
          class="cat-chip"
          :class="{ active: currentCategory === c.id }"
          @click="selectCategory(c.id)"
        >{{ c.name }}</button>
        <button class="cat-chip" :class="{ active: currentCategory === null }" @click="selectCategory(null)">全部</button>
      </div>

      <div class="th-section-title">
        <h2>{{ currentCategoryName || '全部演出' }}</h2>
        <span class="hint">共 {{ shows.length }} 场</span>
      </div>

      <div v-if="loading" class="th-empty">加载中…</div>
      <div v-else-if="shows.length === 0" class="th-empty">
        <div class="big">暂无演出</div>
      </div>
      <div v-else class="th-grid">
        <div v-for="s in shows" :key="s.id" class="th-card" @click="goDetail(s.id)">
          <div class="th-poster">
            <img v-if="s.images" :src="s.images.split(',')[0]" @error="onImgError" />
            <div class="fallback">{{ s.name.slice(0, 2) }}</div>
            <span class="th-badge" :class="{ hot: s.sold > 100 }">{{ statusText(s.status) }}</span>
          </div>
          <div class="th-card-body">
            <h3>{{ s.name }}</h3>
            <div class="th-meta">📍 {{ s.city }} · {{ s.venueName }}</div>
            <div class="th-meta">🗓 {{ formatDate(s.beginTime) }}</div>
            <div class="th-price">¥{{ (s.minPrice / 100).toFixed(0) }} <small>起</small></div>
          </div>
        </div>
      </div>

      <div class="th-section-title">
        <h2>现场动态</h2>
        <span class="hint">来自真实观众的分享</span>
      </div>
      <div class="th-grid">
        <div v-for="p in posts" :key="p.id" class="th-card">
          <div class="th-poster">
            <img v-if="p.images" :src="p.images.split(',')[0]" @error="onImgError" />
            <div class="fallback">动态</div>
          </div>
          <div class="th-card-body">
            <h3>{{ p.title }}</h3>
            <div class="th-meta">👍 {{ p.liked }} · {{ p.name }}</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listCategories, listShowByType, hotPosts } from '../api'

const router = useRouter()
const categories = ref([])
const shows = ref([])
const posts = ref([])
const currentCategory = ref(null)
const loading = ref(false)

const currentCategoryName = computed(() => {
  const c = categories.value.find((x) => x.id === currentCategory.value)
  return c ? c.name : ''
})

const loadShows = async (typeId) => {
  loading.value = true
  try {
    const id = typeId ?? categories.value[0]?.id ?? 1
    const res = await listShowByType(id, 1)
    shows.value = res.data || []
  } finally {
    loading.value = false
  }
}

const selectCategory = (id) => {
  currentCategory.value = id
  if (id === null) {
    loadAllShows()
  } else {
    loadShows(id)
  }
}

const loadAllShows = async () => {
  loading.value = true
  try {
    const all = []
    for (const c of categories.value) {
      const res = await listShowByType(c.id, 1)
      all.push(...(res.data || []))
    }
    shows.value = all
  } finally {
    loading.value = false
  }
}

const goDetail = (id) => router.push(`/show/${id}`)
const onImgError = (e) => { e.target.style.display = 'none' }

const statusText = (s) => ({ 1: '待开票', 2: '售票中', 3: '已售罄', 4: '已结束' }[s] || '售票中')
const formatDate = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

onMounted(async () => {
  const [cRes, pRes] = await Promise.all([listCategories(), hotPosts(1)])
  categories.value = cRes.data || []
  posts.value = pRes.data || []
  await loadAllShows()
})
</script>

<style scoped>
.cat-row { display: flex; flex-wrap: wrap; gap: 10px; }
.cat-chip {
  padding: 8px 18px; border-radius: 999px; cursor: pointer; font-size: 14px;
  border: 1px solid var(--line); background: #fff; color: var(--text);
  transition: all .18s;
}
.cat-chip:hover { border-color: var(--gold); color: var(--crimson); }
.cat-chip.active {
  background: linear-gradient(135deg, var(--gold-soft), var(--gold));
  border-color: var(--gold); color: #1a1206; font-weight: 700;
}
</style>
