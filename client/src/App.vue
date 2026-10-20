<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, getCurrentUser, login, logout } from './api'
import type { CurrentUser } from './api'

const user = ref<CurrentUser | null>(null)
const state = ref<'checking' | 'ready' | 'unavailable'>('checking')
const pending = ref(false)
const email = ref('')
const password = ref('')
const message = ref('')

function errorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return '暂时无法确认结果，请重新检查连接。'
  if (error.code === 'INVALID_CREDENTIALS') return '邮箱或密码不正确，请重新输入。'
  if (error.status === 429) return '尝试次数过多，请稍后再试。'
  if (error.status === 403) return '请求验证失败，请重新检查连接后再操作。'
  if (error.code === 'NETWORK_UNAVAILABLE') return '连接失败或等待超时，请检查连接。操作结果尚未确认。'
  return '服务暂时不可用，请稍后重新检查连接。'
}

async function refreshIdentity() {
  state.value = 'checking'
  user.value = null
  user.value = await getCurrentUser()
  state.value = 'ready'
}

async function checkConnection() {
  if (pending.value) return
  pending.value = true
  message.value = ''
  try {
    await refreshIdentity()
  } catch (error) {
    state.value = 'unavailable'
    message.value = errorMessage(error)
  } finally {
    pending.value = false
  }
}

async function submitLogin() {
  if (pending.value) return
  pending.value = true
  message.value = ''
  const submittedPassword = password.value
  password.value = ''
  try {
    await login(email.value, submittedPassword)
    await refreshIdentity()
    if (!user.value) message.value = '登录后仍未确认身份，请重新检查连接。'
  } catch (error) {
    // 写请求结果可能未知，先隐藏旧身份；重新检查只读身份，不重放登录。
    user.value = null
    state.value = error instanceof ApiError && error.code === 'INVALID_CREDENTIALS' ? 'ready' : 'unavailable'
    message.value = errorMessage(error)
  } finally {
    pending.value = false
  }
}

async function submitLogout() {
  if (pending.value) return
  pending.value = true
  message.value = ''
  try {
    await logout()
    await refreshIdentity()
    message.value = user.value ? '当前环境仍提供已认证身份。' : '已退出登录。'
  } catch (error) {
    user.value = null
    state.value = 'unavailable'
    message.value = errorMessage(error)
  } finally {
    pending.value = false
  }
}

onMounted(checkConnection)
</script>

<template>
  <main class="app-shell">
    <section class="auth-card" aria-labelledby="title" :aria-busy="pending">
      <p class="eyebrow">DailyLanguage</p>
      <h1 id="title">开始你的语言练习</h1>
      <p v-if="state === 'checking'" role="status">正在确认登录状态…</p>
      <template v-else-if="state === 'ready' && user">
        <h2>已连接</h2>
        <p>登录状态已确认。</p>
        <button :disabled="pending" @click="submitLogout">{{ pending ? '处理中…' : '退出登录' }}</button>
      </template>
      <form v-else-if="state === 'ready'" @submit.prevent="submitLogin">
        <h2>登录</h2>
        <label for="email">邮箱</label>
        <input id="email" v-model="email" type="email" autocomplete="username" required :disabled="pending">
        <label for="password">密码</label>
        <input id="password" v-model="password" type="password" autocomplete="current-password" required :disabled="pending">
        <button type="submit" :disabled="pending">{{ pending ? '正在登录…' : '登录' }}</button>
      </form>
      <p v-else>暂时无法确认登录状态。</p>
      <p v-if="message" role="status">{{ message }}</p>
      <button class="secondary" :disabled="pending" @click="checkConnection">重新检查连接</button>
    </section>
  </main>
</template>

<style>
:root { color: #20312e; background: #f4f6f2; font-family: system-ui, sans-serif; }
* { box-sizing: border-box; }
body { margin: 0; }
.app-shell { min-height: 100vh; display: grid; place-items: center; padding: 24px; }
.auth-card { width: 100%; max-width: 480px; padding: 32px; background: white; border: 1px solid #dbe3da; border-radius: 16px; }
.eyebrow { color: #527265; font-weight: 600; }
h1 { font-size: 26px; } h2 { font-size: 20px; }
form { display: grid; gap: 12px; }
input, button { font: inherit; padding: 12px; border: 1px solid #8a9c92; border-radius: 8px; }
button { background: #245e49; color: white; cursor: pointer; }
button:disabled { opacity: .6; cursor: wait; }
button:focus-visible, input:focus-visible { outline: 3px solid #c27c29; outline-offset: 3px; }
.secondary { margin-top: 16px; background: white; color: #245e49; }
</style>
