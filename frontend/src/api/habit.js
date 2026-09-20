import request from './request'

// 与后端 PageRequest 保持一致：页码从 1 开始，默认每页 20 条。
export function getHabits({ page = 1, pageSize = 20 } = {}) {
  return request({ url: '/habits', data: { page, pageSize } })
}

// 只提交名称与描述；身份和 ID 均由后端决定。
export function createHabit(data) {
  return request({ url: '/habits', method: 'POST', data })
}

// ID 直接按字符串构造路径，不转换为 JavaScript Number。
export function getTodayStatus(habitId) {
  return request({ url: '/habits/' + encodeURIComponent(habitId) + '/checkins/today' })
}

export function checkinToday(habitId) {
  return request({ url: '/habits/' + encodeURIComponent(habitId) + '/checkins/today', method: 'PUT' })
}

export function getStreak(habitId) {
  return request({ url: '/habits/' + encodeURIComponent(habitId) + '/streak' })
}
