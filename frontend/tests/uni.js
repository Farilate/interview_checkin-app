import { vi } from 'vitest'

// 每个测试独立内存存储；请求只能通过替身完成，不连接真实服务。
export function mockUni() {
  const storage = new Map()
  const uni = {
    getStorageSync: vi.fn(key => storage.get(key)),
    setStorageSync: vi.fn((key, value) => storage.set(key, value)),
    removeStorageSync: vi.fn(key => storage.delete(key)),
    request: vi.fn(options => options.success({ statusCode: 200, data: { code: 0, message: 'ok', data: null } })),
  }
  vi.stubGlobal('uni', uni)
  return uni
}
