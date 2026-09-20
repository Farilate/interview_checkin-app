<template>
  <view class="page">
    <text class="title">登录验证</text>
    <text v-if="loading" class="message">正在获取当前用户…</text>
    <text v-else-if="username" class="message">当前登录用户：{{ username }}</text>
    <text v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</text>
    <button v-if="errorMessage" :disabled="loading || loggingOut" @click="loadUser">重试</button>
    <button type="primary" :loading="loggingOut" :disabled="loggingOut" @click="signOut">{{ loggingOut ? '退出中…' : '退出登录' }}</button>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getCurrentUser, logout } from '../../api/auth'
import { clearToken } from '../../utils/auth'

const username = ref('')
const loading = ref(false)
const loggingOut = ref(false)
const errorMessage = ref('')

function returnToLogin() {
  uni.reLaunch({ url: '/pages/login/login' })
}

async function loadUser() {
  if (loading.value || loggingOut.value) return
  loading.value = true
  errorMessage.value = ''
  username.value = ''
  try {
    // 用户名来自真实 /auth/me 响应，本页不实现 Habit 业务。
    const user = await getCurrentUser()
    if (!loggingOut.value) username.value = user.username
  } catch (error) {
    if (loggingOut.value) return
    if (error.status === 401) returnToLogin()
    else errorMessage.value = error.message || '获取用户信息失败'
  } finally {
    loading.value = false
  }
}

async function signOut() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    await logout()
  } catch {
    // 服务端退出失败也必须清除本地身份，不能把用户困在验证页。
  } finally {
    clearToken()
    returnToLogin()
    loggingOut.value = false
  }
}

onShow(loadUser)
</script>

<style scoped>
.page { max-width: 420px; margin: 60px auto; padding: 24px; }
.title { display: block; font-size: 26px; font-weight: bold; margin-bottom: 32px; }
.message { display: block; margin-bottom: 24px; }
.error { display: block; color: #b42318; margin-bottom: 16px; }
button { margin-top: 16px; }
</style>
