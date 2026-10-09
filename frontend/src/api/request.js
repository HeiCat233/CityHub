import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

// 生产环境走 VITE_API_BASE（OSS 静态站点 + 服务器 nginx 网关）
// 本地开发走 vite 代理的 /api
const baseURL = import.meta.env.VITE_API_BASE || '/api'

const request = axios.create({
  baseURL,
  timeout: 15000
})

// 请求拦截：自动携带 token
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.authorization = token
  }
  return config
})

// 响应拦截：统一处理 Result 结构 { success, errorMsg, data }
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res && res.success === false) {
      ElMessage.error(res.errorMsg || '请求失败')
      return Promise.reject(new Error(res.errorMsg || '请求失败'))
    }
    return res
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      ElMessage.warning('请先登录')
      localStorage.removeItem('token')
      router.push('/login')
    } else if (status === 429) {
      ElMessage.error('操作过于频繁，请稍后再试')
    } else {
      ElMessage.error(error.message || '网络异常')
    }
    return Promise.reject(error)
  }
)

export default request
