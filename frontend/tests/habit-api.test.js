import { beforeEach, expect, it } from 'vitest'
import { mockUni } from './uni'
import { getHabits, createHabit, getTodayStatus, checkinToday, getStreak } from '../src/api/habit'
let uni
beforeEach(() => { uni = mockUni() })
it.each([[undefined, 1, 20], [{ page: 2, pageSize: 5 }, 2, 5], [{ page: 3 }, 3, 20]])('分页参数 %j 使用 page/pageSize', async (input, page, pageSize) => {
  await getHabits(input)
  expect(uni.request).toHaveBeenCalledWith(expect.objectContaining({ url: 'http://localhost:8080/api/v1/habits', method: 'GET', data: { page, pageSize } }))
})
it('创建提交 POST name/description，不生成 ID', async () => {
  const data = { name: '阅读', description: null }
  await createHabit(data)
  expect(uni.request).toHaveBeenCalledWith(expect.objectContaining({ url: 'http://localhost:8080/api/v1/habits', method: 'POST', data }))
})
for (const [fn, suffix, method] of [[getTodayStatus, '/checkins/today', 'GET'], [checkinToday, '/checkins/today', 'PUT'], [getStreak, '/streak', 'GET']]) {
  it.each(['9007199254740993', '000123', 'a/b ?#中文%']) (method + suffix + ' 保留并编码字符串 ID %s', async id => {
    await fn(id)
    expect(uni.request).toHaveBeenCalledWith(expect.objectContaining({ url: 'http://localhost:8080/api/v1/habits/' + encodeURIComponent(id) + suffix, method }))
    expect(uni.request.mock.calls[0][0].data).toBeUndefined()
  })
}
