import request from './request'

// 认证接口均通过同一请求层添加令牌、解包响应和处理错误。
export function login(data) {
  return request({ url: '/auth/login', method: 'POST', data })
}

export function getCurrentUser() {
  return request({ url: '/auth/me' })
}

export function logout() {
  return request({ url: '/auth/logout', method: 'POST' })
}
