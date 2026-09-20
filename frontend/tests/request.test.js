import { beforeEach, expect, it } from 'vitest'
import { mockUni } from './uni'
import request from '../src/api/request'
import { setToken, getToken } from '../src/utils/auth'
let uni
beforeEach(() => { uni = mockUni() })
function respond(statusCode, data) { uni.request.mockImplementation(options => options.success({ statusCode, data })) }
it('无令牌不携带 Authorization', async () => {
  await request({ url: '/habits' })
  expect(uni.request.mock.calls[0][0].header).not.toHaveProperty('Authorization')
})
it('每次请求读取最新令牌并携带 Bearer', async () => {
  setToken('first'); await request({ url: '/auth/me' })
  setToken('second'); await request({ url: '/auth/me' })
  expect(uni.request.mock.calls[0][0].header.Authorization).toBe('Bearer first')
  expect(uni.request.mock.calls[1][0].header.Authorization).toBe('Bearer second')
})
it.each([200, 201])('HTTP %i 且 code=0 解包 data，不解析 message', async status => {
  respond(status, { code: 0, message: '未登录', data: { id: '9007199254740993' } })
  await expect(request({ url: '/habits' })).resolves.toEqual({ id: '9007199254740993' })
})
it.each([409, 200])('HTTP %i 的业务错误保留结构化字段', async status => {
  respond(status, { code: 40901, message: 'ok', data: null })
  await expect(request({ url: '/habits' })).rejects.toEqual({ status, code: 40901, message: 'ok' })
})
it('401 根据 HTTP 状态清令牌，与提示文字无关', async () => {
  setToken('token'); respond(401, { code: 40101, message: 'ok', data: null })
  await expect(request({ url: '/auth/me' })).rejects.toMatchObject({ status: 401, code: 40101 })
  expect(getToken()).toBe('')
})
it('503 即使消息包含未登录也不清令牌', async () => {
  setToken('token'); respond(503, { code: 50301, message: '未登录', data: null })
  await expect(request({ url: '/auth/me' })).rejects.toMatchObject({ status: 503, code: 50301 })
  expect(getToken()).toBe('token')
})
it('网络失败拒绝为 status=0/code=null，保留令牌', async () => {
  setToken('token'); uni.request.mockImplementation(options => options.fail({ errMsg: 'network' }))
  await expect(request({ url: '/habits' })).rejects.toEqual({ status: 0, code: null, message: expect.any(String) })
  expect(getToken()).toBe('token')
})
it('非 2xx 即使 code=0 仍拒绝', async () => {
  respond(500, { code: 0, message: 'ok' })
  await expect(request({ url: '/habits' })).rejects.toMatchObject({ status: 500, code: 0 })
})
it.each([null, '<html>error</html>', { code: '0' }])('异常响应 %j 安全拒绝', async body => {
  respond(200, body)
  await expect(request({ url: '/habits' })).rejects.toMatchObject({ status: 200, code: null, message: expect.any(String) })
})
