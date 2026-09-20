import { getToken, clearToken } from '../utils/auth'

const BASE_URL = 'http://localhost:8080/api/v1'

// 统一以 { status, code, message } 拒绝失败；网络错误没有 HTTP 状态及业务码。
export default function request({ url, method = 'GET', data }) {
  return new Promise((resolve, reject) => {
    const token = getToken()
    const header = { 'Content-Type': 'application/json' }
    if (token) header.Authorization = 'Bearer ' + token

    uni.request({
      url: BASE_URL + url,
      method,
      data,
      header,
      timeout: 10000,
      success(response) {
        const status = response.statusCode
        const body = response.data
        // 只依据状态码清理身份，503 等错误不能被当作退出登录。
        if (status === 401) clearToken()
        if (status >= 200 && status < 300 && body?.code === 0) {
          resolve(body.data)
          return
        }
        reject({
          status,
          code: typeof body?.code === 'number' ? body.code : null,
          message: typeof body?.message === 'string' ? body.message : '请求失败，请稍后重试',
        })
      },
      fail() {
        // 浏览器可能把连接故障和 CORS 拦截都呈现为网络失败，不按消息猜测原因。
        reject({ status: 0, code: null, message: '无法连接服务器，请检查网络后重试' })
      },
    })
  })
}
