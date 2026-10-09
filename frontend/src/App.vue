<template>
  <div class="th-shell">
    <header class="th-nav">
      <router-link to="/" class="th-logo">Ticket<span>Hub</span></router-link>
      <nav class="th-nav-links">
        <router-link to="/">演出</router-link>
        <router-link to="/orders">我的订单</router-link>
        <router-link to="/profile">个人中心</router-link>
      </nav>
      <div class="th-nav-right">
        <template v-if="user">
          <span class="th-user">你好，{{ user.nickName }}</span>
          <el-button size="small" @click="handleLogout">退出</el-button>
        </template>
        <template v-else>
          <el-button size="small" type="primary" @click="$router.push('/login')">登录 / 注册</el-button>
        </template>
      </div>
    </header>
    <main>
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getMe, logout } from './api'

const router = useRouter()
const user = ref(null)

const loadUser = async () => {
  if (!localStorage.getItem('token')) return
  try {
    const res = await getMe()
    user.value = res.data
  } catch (e) {
    user.value = null
  }
}

const handleLogout = async () => {
  try { await logout() } catch (e) { /* 忽略 */ }
  localStorage.removeItem('token')
  user.value = null
  ElMessage.success('已退出登录')
  router.push('/')
}

onMounted(loadUser)

// 暴露给子页面刷新登录态
window.addEventListener('tickethub:login', loadUser)
</script>
