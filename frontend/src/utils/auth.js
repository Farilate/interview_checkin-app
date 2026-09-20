// 登录令牌统一存储入口；页面不直接操作本地存储。
const TOKEN_KEY = 'checkin.auth.token'

export function getToken() {
  const token = uni.getStorageSync(TOKEN_KEY)
  return typeof token === 'string' ? token : ''
}

export function setToken(token) {
  uni.setStorageSync(TOKEN_KEY, token)
}

export function clearToken() {
  uni.removeStorageSync(TOKEN_KEY)
}
