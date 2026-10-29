<template>
  <div class="page-inner login-page">
    <section class="ticket login-ticket">
      <div class="stub">
        <span class="stub-vert">入场券</span>
      </div>
      <div class="tear"></div>
      <div class="ticket-main">
        <h1 class="page-title">登录</h1>
        <p class="addr">验证码不会真的发短信，会打印在后端控制台日志里。</p>
        <div class="form">
          <div class="field">
            <label for="phone">手机号</label>
            <input id="phone" v-model="phone" placeholder="种子数据可用 13800000001" />
          </div>
          <div class="field">
            <label for="code">验证码</label>
            <div class="row">
              <input id="code" v-model="code" placeholder="6 位验证码" />
              <button class="btn btn-ghost" :disabled="cooldown > 0" @click="sendCode">
                {{ cooldown > 0 ? cooldown + ' 秒后重发' : '发送验证码' }}
              </button>
            </div>
          </div>
          <div>
            <button class="btn btn-primary" @click="login">登录</button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, onUnmounted } from 'vue'
import request from '../api'
import { toast } from '../toast'

const phone = ref('13800000001')
const code = ref('')
const cooldown = ref(0)
let timer = null

async function sendCode() {
  if (!phone.value) return toast('先填手机号再发送验证码', 'err')
  await request.post('/user/code?phone=' + phone.value)
  toast('验证码已发送，到后端控制台查看')
  cooldown.value = 60
  timer = setInterval(() => {
    cooldown.value--
    if (cooldown.value <= 0) clearInterval(timer)
  }, 1000)
}

async function login() {
  if (!code.value) return toast('请输入 6 位验证码', 'err')
  const token = await request.post('/user/login', { phone: phone.value, code: code.value })
  localStorage.setItem('token', token)
  // 整页刷新让导航栏的登录态立即生效
  window.location.href = '/'
}

onUnmounted(() => clearInterval(timer))
</script>
