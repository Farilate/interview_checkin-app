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
import { getHabits, createHabit } from '../../api/habit'
import { clearToken } from '../../utils/auth'

const username = ref('')
const userLoading = ref(false)
const userError = ref('')
const loggingOut = ref(false)
const leaving = ref(false)
const habits = ref([])
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
  try {
    const data = await getHabits({ page: targetPage, pageSize: pageSize.value })
    if (leaving.value) return
    // 严格使用 PageResponse 的字段，只有请求成功才切换已展示的页码。
    habits.value = data.items
    total.value = data.total
    page.value = data.page
    pageSize.value = data.pageSize
    listReady.value = true
  } catch (error) {
    if (!leaving.value && !handleUnauthorized(error)) listError.value = error.message || '加载习惯失败'
  } finally {
    listLoading.value = false
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
