import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'home', component: () => import('../views/Home.vue') },
  { path: '/show/:id', name: 'showDetail', component: () => import('../views/ShowDetail.vue') },
  { path: '/ticket/:tierId', name: 'ticket', component: () => import('../views/Ticket.vue') },
  { path: '/orders', name: 'orders', component: () => import('../views/Orders.vue') },
  { path: '/profile', name: 'profile', component: () => import('../views/Profile.vue') },
  { path: '/login', name: 'login', component: () => import('../views/Login.vue') }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 需要登录的路由
const authPaths = ['/orders', '/profile']
router.beforeEach((to) => {
  if (authPaths.includes(to.path) && !localStorage.getItem('token')) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
