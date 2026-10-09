<template>
  <div class="login-wrap">
    <div class="login-card">
      <h2 class="display">登录 TicketHub</h2>
      <p class="sub">演示环境：验证码会直接显示在下方，无需短信</p>

      <el-form :model="form" label-position="top">
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="请输入 11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="验证码">
          <div class="code-row">
            <el-input v-model="form.code" placeholder="6 位验证码" maxlength="6" />
            <el-button :disabled="countdown > 0" @click="handleSendCode">
              {{ countdown > 0 ? `${countdown}s` : '获取验证码' }}
            </el-button>
          </div>
        </el-form-item>
      </el-form>

      <div v-if="demoCode" class="demo-code">
        演示验证码：<b>{{ demoCode }}</b>
      </div>

      <el-button type="primary" class="submit" :loading="loading" @click="handleLogin">
        登录 / 注册
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { sendCode, login } from '../api'

const router = useRouter()
const route = useRoute()
const form = reactive({ phone: '', code: '' })
const demoCode = ref('')
const countdown = ref(0)
const loading = ref(false)

const handleSendCode = async () => {
  if (!/^1[3-9]\d{9}$/.test(form.phone)) {
    ElMessage.warning('请输入正确的手机号')
    return
  }
  const res = await sendCode(form.phone)
  demoCode.value = res.data
  form.code = res.data
  ElMessage.success('验证码已生成')
  countdown.value = 60
  const t = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) clearInterval(t)
  }, 1000)
}

const handleLogin = async () => {
  loading.value = true
  try {
    const res = await login({ phone: form.phone, code: form.code })
    localStorage.setItem('token', res.data)
    // 通知 App 刷新登录态
    window.dispatchEvent(new Event('tickethub:login'))
    ElMessage.success('登录成功')
    router.push(route.query.redirect || '/')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: calc(100vh - 68px); display: flex; align-items: center; justify-content: center;
  background: radial-gradient(120% 120% at 20% 10%, #2a2f3d, var(--ink));
  padding: 40px 20px;
}
.login-card {
  width: 100%; max-width: 420px; background: #fff; border-radius: 18px;
  padding: 40px 36px; box-shadow: 0 24px 60px rgba(0,0,0,.35);
}
.login-card h2 { margin: 0 0 8px; font-size: 26px; }
.sub { color: var(--text-mute); font-size: 13px; margin: 0 0 24px; }
.code-row { display: flex; gap: 10px; width: 100%; }
.demo-code {
  background: rgba(217,164,65,.14); border: 1px dashed var(--gold);
  border-radius: 10px; padding: 10px 14px; font-size: 14px; margin-bottom: 18px;
}
.demo-code b { color: var(--crimson); font-size: 18px; letter-spacing: 2px; }
.submit { width: 100%; height: 46px; font-size: 16px; }
</style>
