import { beforeEach, expect, it } from 'vitest'
import { mockUni } from './uni'
import { getToken, setToken, clearToken } from '../src/utils/auth'
let uni
beforeEach(() => { uni = mockUni() })
it('统一键保存并读取令牌', () => {
  setToken('test-token')
  expect(uni.setStorageSync).toHaveBeenCalledWith('checkin.auth.token', 'test-token')
  expect(getToken()).toBe('test-token')
})
it('清除令牌后读取为空', () => {
  setToken('test-token'); clearToken()
  expect(uni.removeStorageSync).toHaveBeenCalledWith('checkin.auth.token')
  expect(getToken()).toBe('')
})
it.each([undefined, null, 123, {}, false])('非字符串存储值 %j 安全返回空串', value => {
  uni.getStorageSync.mockReturnValue(value)
  expect(getToken()).toBe('')
})
