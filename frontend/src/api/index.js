import request from './request'

// ---------- 用户 ----------
export const sendCode = (phone) => request.post(`/user/code?phone=${phone}`)
export const login = (data) => request.post('/user/login', data)
export const logout = () => request.post('/user/logout')
export const getMe = () => request.get('/user/me')
export const sign = () => request.post('/user/sign')
export const signCount = () => request.get('/user/sign/count')

// ---------- 演出 ----------
export const listCategories = () => request.get('/show-category/list')
export const getShow = (id) => request.get(`/show/${id}`)
export const listShowByType = (typeId, current = 1, x, y) =>
  request.get('/show/of/type', { params: { typeId, current, x, y } })
export const searchShow = (name, current = 1) =>
  request.get('/show/of/name', { params: { name, current } })

// ---------- 票档与抢票 ----------
export const listTiers = (showId) => request.get(`/tier/list/${showId}`)
export const seckill = (tierId) => request.post(`/ticket-order/seckill/${tierId}`)
export const myOrders = () => request.get('/ticket-order/my')
export const payOrder = (orderId) => request.post(`/ticket-order/pay/${orderId}`)

// ---------- 动态 ----------
export const hotPosts = (current = 1) => request.get('/post/hot', { params: { current } })
export const getPost = (id) => request.get(`/post/${id}`)
export const likePost = (id) => request.put(`/post/like/${id}`)
export const followPosts = (lastId, offset) =>
  request.get('/post/of/follow', { params: { lastId, offset } })

// ---------- 关注 ----------
export const follow = (id, isFollow) => request.put(`/follow/${id}/${isFollow}`)
export const isFollow = (id) => request.get(`/follow/or/not/${id}`)
