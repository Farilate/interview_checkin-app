import { beforeEach, expect, it } from 'vitest'
import { mockUni } from './uni'
import { login, getCurrentUser, logout } from '../src/api/auth'
let uni
beforeEach(() => { uni = mockUni() })
it('login 使用 POST 并原样传递密码', async () => {
  const data = { username: 'demo', password: ' pass word ' }
  await login(data)
  expect(uni.request).toHaveBeenCalledWith(expect.objectContaining({ url: 'http://localhost:8080/api/v1/auth/login', method: 'POST', data }))
})
it.each([[getCurrentUser, '/auth/me', 'GET'], [logout, '/auth/logout', 'POST']])('认证接口 %s 使用正确 URL/method', async (fn, path, method) => {
  await fn()
  expect(uni.request).toHaveBeenCalledWith(expect.objectContaining({ url: 'http://localhost:8080/api/v1' + path, method }))
})
