<template>
  <view class="page">
    <text class="title">我的习惯</text>
    <text v-if="userLoading" class="message">正在获取当前用户…</text>
    <text v-else-if="username" class="message">当前登录用户：{{ username }}</text>
    <view v-if="userError">
      <text class="error" role="alert">{{ userError }}</text>
      <button :disabled="userLoading || leaving" @click="loadUser">重试用户信息</button>
    </view>
    <view class="actions">
      <button type="primary" :disabled="submitting || leaving" @click="openForm">新建习惯</button>
      <button :loading="loggingOut" :disabled="leaving || submitting" @click="signOut">退出登录</button>
    </view>

    <view v-if="formOpen" class="card">
      <text class="heading">新建习惯</text>
      <text class="label">名称（必填，最多 50 个字符）</text>
      <input v-model="name" class="input" :maxlength="-1" :disabled="submitting" placeholder="例如：每日阅读" />
      <text class="label">描述（选填，最多 200 个字符）</text>
      <textarea v-model="description" class="input description" :maxlength="-1" :disabled="submitting" placeholder="描述这个习惯" />
      <text v-if="formError" class="error" role="alert">{{ formError }}</text>
      <view class="actions">
        <button type="primary" :loading="submitting" :disabled="submitting || listLoading || leaving" @click="submitHabit">{{ submitting ? '创建中…' : '创建' }}</button>
        <button :disabled="submitting" @click="formOpen = false">取消</button>
      </view>
    </view>

    <text v-if="notice" class="message" role="status">{{ notice }}</text>
    <text v-if="listLoading" class="message">正在加载习惯…</text>
    <view v-else-if="listError">
      <text class="error" role="alert">{{ listError }}</text>
      <button :disabled="leaving || submitting" @click="loadHabits(requestedPage)">重试列表</button>
    </view>
    <template v-else-if="listReady">
      <text v-if="habits.length === 0" class="message">{{ total === 0 ? '还没有习惯，点击“新建习惯”开始吧。' : '当前页没有习惯，请返回上一页。' }}</text>
      <!-- ID 保持后端十进制字符串，避免 BIGINT 转 Number 后丢失精度。 -->
      <view v-for="habit in habits" :key="habit.id" class="card">
        <text class="heading">{{ habit.name }}</text>
        <text v-if="habit.description" class="habit-description">{{ habit.description }}</text>
        <view v-if="checkinStates[habit.id]" class="checkin-status">
          <text v-if="checkinStates[habit.id].todayLoading" class="message">今日状态：加载中…</text>
          <text v-else-if="checkinStates[habit.id].todayError" class="error" role="alert">今日状态：{{ checkinStates[habit.id].todayError }}</text>
          <text v-else class="message">今日状态：{{ checkinStates[habit.id].checkedIn === true ? '已打卡' : '未打卡' }}</text>
          <text v-if="checkinStates[habit.id].streakLoading" class="message">当前连续：加载中…</text>
          <text v-else-if="checkinStates[habit.id].streakError" class="error" role="alert">当前连续：{{ checkinStates[habit.id].streakError }}</text>
          <text v-else class="message">当前连续：{{ checkinStates[habit.id].streak }} 天</text>
          <text v-if="checkinStates[habit.id].actionError" class="error" role="alert">{{ checkinStates[habit.id].actionError }}</text>
          <text v-if="checkinStates[habit.id].notice" class="message" role="status">{{ checkinStates[habit.id].notice }}</text>
          <button type="primary"
            :loading="checkinStates[habit.id].checking"
            :disabled="leaving || checkinStates[habit.id].checking || checkinStates[habit.id].todayLoading || checkinStates[habit.id].checkedIn !== false || checkinStates[habit.id].unavailable"
            @click="submitCheckin(habit.id)">
            {{ checkinStates[habit.id].checking ? '打卡并同步中…' : checkinStates[habit.id].checkedIn === true ? '今日已打卡' : '立即打卡' }}
          </button>
          <button v-if="checkinStates[habit.id].todayError || checkinStates[habit.id].streakError || checkinStates[habit.id].actionError"
            :disabled="leaving || checkinStates[habit.id].checking || checkinStates[habit.id].todayLoading || checkinStates[habit.id].streakLoading"
            @click="refreshCheckin(habit.id)">重新同步状态</button>
        </view>
      </view>
    </template>
    <view v-if="listReady" class="pagination">
      <text>第 {{ page }} 页 · 共 {{ total }} 个习惯</text>
      <view class="actions">
        <button :disabled="page <= 1 || listLoading || submitting || leaving" @click="loadHabits(page - 1)">上一页</button>
        <button :disabled="page * pageSize >= total || listLoading || submitting || leaving" @click="loadHabits(page + 1)">下一页</button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import { getCurrentUser, logout } from '../../api/auth'
import { getHabits, createHabit, getTodayStatus, checkinToday, getStreak } from '../../api/habit'
import { clearToken } from '../../utils/auth'

const username = ref('')
const userLoading = ref(false)
const userError = ref('')
const loggingOut = ref(false)
const leaving = ref(false)
const habits = ref([])
// 每页重新建立以字符串 ID 为键的状态；页版本阻止旧页异步响应覆盖新页。
const checkinStates = ref({})
let pageVersion = 0
const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const requestedPage = ref(1)
const listLoading = ref(false)
const listReady = ref(false)
const listError = ref('')
const formOpen = ref(false)
const name = ref('')
const description = ref('')
const submitting = ref(false)
const formError = ref('')
const notice = ref('')

function returnToLogin() {
  leaving.value = true
  uni.reLaunch({ url: '/pages/login/login' })
}

// request 层负责清 Token；页面仅根据 HTTP 状态处理导航，不匹配错误消息。
function handleUnauthorized(error) {
  if (error.status !== 401) return false
  returnToLogin()
  return true
}

async function loadUser() {
  if (userLoading.value || leaving.value) return
  userLoading.value = true
  userError.value = ''
  try {
    const user = await getCurrentUser()
    if (!leaving.value) username.value = user.username
  } catch (error) {
    if (!leaving.value && !handleUnauthorized(error)) userError.value = error.message || '获取用户信息失败'
  } finally {
    userLoading.value = false
  }
}

async function loadHabits(targetPage = 1) {
  if (listLoading.value || leaving.value) return
  listLoading.value = true
  listError.value = ''
  requestedPage.value = targetPage
  const version = ++pageVersion
  try {
    const data = await getHabits({ page: targetPage, pageSize: pageSize.value })
    if (leaving.value) return
    // 严格使用 PageResponse 的字段，只有请求成功才切换已展示的页码。
    habits.value = data.items
    total.value = data.total
    page.value = data.page
    pageSize.value = data.pageSize
    listReady.value = true
    checkinStates.value = Object.fromEntries(data.items.map(habit => [habit.id, {
      checkedIn: null, streak: null, todayLoading: true, streakLoading: true,
      todayError: '', streakError: '', checking: false, actionError: '', notice: '', unavailable: false,
      refreshVersion: 0,
    }]))
    // 每个 Habit 的两个查询独立处理失败，不让单项故障影响列表或其他卡片。
    for (const habit of data.items) refreshCheckin(habit.id, version)
  } catch (error) {
    if (!leaving.value && !handleUnauthorized(error)) listError.value = error.message || '加载习惯失败'
  } finally {
    listLoading.value = false
  }
}

function isCurrentCard(habitId, version) {
  return !leaving.value && version === pageVersion && !!checkinStates.value[habitId]
}

function cardError(error) {
  return error.code === 40401 ? '习惯不存在或无权访问，请刷新列表' : (error.message || '请求失败，请重试')
}

async function refreshCheckin(habitId, version = pageVersion) {
  if (!isCurrentCard(habitId, version)) return
  const state = checkinStates.value[habitId]
  const refreshVersion = ++state.refreshVersion
  state.unavailable = false
  // 开始查询就清除旧值，刷新失败时不能把旧状态当作最新结果。
  state.checkedIn = null
  state.streak = null
  async function query(kind, fetchData, field) {
    state[kind + 'Loading'] = true
    state[kind + 'Error'] = ''
    try {
      const data = await fetchData(habitId)
      if (isCurrentCard(habitId, version) && state.refreshVersion === refreshVersion) state[field] = data[field]
    } catch (error) {
      if (leaving.value) return
      // 401 始终沿用统一认证处理；旧页普通错误不污染新页。
      if (handleUnauthorized(error)) return
      if (isCurrentCard(habitId, version) && state.refreshVersion === refreshVersion) {
        state[kind + 'Error'] = cardError(error)
        if (error.code === 40401) state.unavailable = true
      }
    } finally {
      if (isCurrentCard(habitId, version) && state.refreshVersion === refreshVersion) state[kind + 'Loading'] = false
    }
  }
  await Promise.all([query('today', getTodayStatus, 'checkedIn'), query('streak', getStreak, 'streak')])
}

async function submitCheckin(habitId) {
  const version = pageVersion
  if (!isCurrentCard(habitId, version)) return
  const state = checkinStates.value[habitId]
  if (state.checking || state.todayLoading || state.checkedIn !== false || state.unavailable) return
  state.checking = true
  state.actionError = ''
  state.notice = ''
  // 作废打卡前仍在途的查询，避免旧 streak 覆盖写后查询结果。
  ++state.refreshVersion
  try {
    const result = await checkinToday(habitId)
    if (!isCurrentCard(habitId, version)) return
    if (result.created === true) state.notice = '本次打卡成功'
    else if (result.created === false) state.notice = '今日已打过卡，正在同步最新状态'
    // 两种幂等成功均重新读取服务器状态，不自行设置 today 或增加 streak。
    await refreshCheckin(habitId, version)
  } catch (error) {
    if (!leaving.value && !handleUnauthorized(error) && isCurrentCard(habitId, version)) {
      state.actionError = cardError(error)
      if (error.code === 40401) state.unavailable = true
      // 网络失败时写入结果可能未知，重新读取状态后再决定是否允许重试。
      await refreshCheckin(habitId, version)
    }
  } finally {
    if (isCurrentCard(habitId, version)) state.checking = false
  }
}

function openForm() {
  formError.value = ''
  notice.value = ''
  formOpen.value = true
}

async function submitHabit() {
  if (submitting.value || listLoading.value || leaving.value) return
  formError.value = ''
  notice.value = ''
  // Java String.trim 仅去掉两端 U+0000 至 U+0020；长度按 Unicode 码点计算。
  const normalizedName = name.value.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '')
  if (!normalizedName.trim()) {
    formError.value = '请输入习惯名称'
    return
  }
  if ([...normalizedName].length > 50 || [...description.value].length > 200) {
    formError.value = '名称最多 50 个字符，描述最多 200 个字符'
    return
  }
  submitting.value = true
  try {
    // 描述保留原文；空白转 NULL 的最终规则由后端统一执行。
    await createHabit({ name: normalizedName, description: description.value })
    if (leaving.value) return
    name.value = ''
    description.value = ''
    formOpen.value = false
    notice.value = '习惯创建成功'
    // 不插入内存假数据，重新查询第一页，按后端排序展示新记录。
    await loadHabits(1)
  } catch (error) {
    if (!leaving.value && !handleUnauthorized(error)) {
      formError.value = error.code === 40901 ? '你已创建同名习惯，请换一个名称' : (error.message || '创建失败，请稍后重试')
    }
  } finally {
    submitting.value = false
  }
}

async function signOut() {
  if (leaving.value || submitting.value) return
  loggingOut.value = true
  leaving.value = true
  try {
    await logout()
  } catch {
    // 退出请求失败也清除本地身份；在途列表请求不能重新更新页面。
  } finally {
    clearToken()
    returnToLogin()
    loggingOut.value = false
  }
}

onShow(() => {
  loadUser()
  loadHabits(1)
})
onUnload(() => { leaving.value = true })
</script>

<style scoped>
.page { max-width: 640px; margin: 24px auto; padding: 20px; }
.title { display: block; font-size: 26px; font-weight: bold; margin-bottom: 20px; }
.message, .error { display: block; margin: 16px 0; }
.error { color: #b42318; }
.actions { display: flex; gap: 12px; margin: 16px 0; }
.actions button { flex: 1; margin: 0; font-size: 16px; }
.card { background: #fff; border: 1px solid #ddd; border-radius: 8px; padding: 20px; margin: 16px 0; }
.heading { display: block; font-size: 19px; font-weight: bold; overflow-wrap: anywhere; }
.label { display: block; margin: 16px 0 8px; }
.input { box-sizing: border-box; border: 1px solid #ccc; border-radius: 6px; padding: 12px; }
input.input { height: 48px; }
.description { width: 100%; height: 110px; }
.habit-description { display: block; margin-top: 12px; white-space: pre-wrap; overflow-wrap: anywhere; color: #555; }
.pagination { margin-top: 24px; text-align: center; }
</style>
