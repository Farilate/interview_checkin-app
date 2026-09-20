<template>
  <view class="page">
    <text class="title">登录习惯打卡</text>
    <text class="label">用户名</text>
    <input v-model="username" class="input" placeholder="请输入用户名" :disabled="loading" />
    <text class="label">密码</text>
    <input v-model="password" class="input" type="password" password placeholder="请输入密码" :disabled="loading" @confirm="submit" />
    <text v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</text>
    <button type="primary" :loading="loading" :disabled="loading" @click="submit">{{ loading ? '登录中…' : '登录' }}</button>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { login } from '../../api/auth'
import { setToken } from '../../utils/auth'

const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref('')

async function submit() {
  if (loading.value) return
  errorMessage.value = ''
  if (!username.value.trim() || !password.value.trim()) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    // 密码仅校验非空，原样提交；令牌只使用后端实际返回的 data.token。
    const data = await login({ username: username.value.trim(), password: password.value })
    if (typeof data?.token !== 'string' || !data.token.trim()) {
      errorMessage.value = '登录响应缺少有效令牌，请稍后重试'
      return
    }
    setToken(data.token)
    password.value = ''
    uni.reLaunch({ url: '/pages/habits/habits' })
  } catch (error) {
    errorMessage.value = error.message || '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.page { max-width: 420px; margin: 60px auto; padding: 24px; }
.title { display: block; font-size: 26px; font-weight: bold; margin-bottom: 32px; }
.label { display: block; margin-bottom: 8px; }
.input { border: 1px solid #ccc; border-radius: 6px; padding: 12px; margin-bottom: 20px; }
.error { display: block; color: #b42318; margin-bottom: 16px; }
</style>
