<template>
  <div class="page">
    <h2>登录</h2>
    <p class="tip">验证码不会真的发短信，会打印在<b>后端控制台日志</b>里（“发送验证码成功xxxxxx”）</p>
    <div class="form">
      <input v-model="phone" placeholder="手机号，种子数据可用 13800000001" />
      <div class="row">
        <input v-model="code" placeholder="6 位验证码" />
        <button :disabled="sending" @click="sendCode">{{ sending ? '已发送' : '发送验证码' }}</button>
      </div>
      <button class="primary" @click="login">登录</button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import request from '../api'

const phone = ref('13800000001')
const code = ref('')
const sending = ref(false)

async function sendCode() {
  await request.post('/user/code?phone=' + phone.value)
  sending.value = true
  alert('验证码已发送，请到后端控制台日志查看')
}

async function login() {
  const token = await request.post('/user/login', { phone: phone.value, code: code.value })
  localStorage.setItem('token', token)
  // 整页刷新让导航栏的登录态立即生效
  window.location.href = '/'
}
</script>
