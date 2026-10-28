<template>
  <div class="layout">
    <header class="nav">
      <div class="brand" @click="$router.push('/')">🎫 ShowTicket · 演出抢票</div>
      <nav>
        <router-link to="/">首页</router-link>
        <router-link to="/orders">我的订单</router-link>
        <span v-if="logged" class="user" @click="logout">退出</span>
        <router-link v-else to="/login">登录</router-link>
      </nav>
    </header>
    <main>
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
// localStorage 非响应式，简单场景下用完整刷新兜底（登录/退出后 location.href 跳转）
const logged = computed(() => !!localStorage.getItem('token'))

function logout() {
  localStorage.removeItem('token')
  window.location.href = '/login'
  router.push('/login')
}
</script>
