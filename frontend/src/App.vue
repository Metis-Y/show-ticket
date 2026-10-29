<template>
  <header class="topbar">
    <div class="topbar-inner">
      <div class="brand" @click="$router.push('/')">
        <b>ShowTicket</b>
        <span>上海现场演出</span>
      </div>
      <nav class="menu">
        <router-link to="/">场馆</router-link>
        <router-link to="/orders">我的订单</router-link>
        <button v-if="logged" class="menu-btn" @click="logout">退出</button>
        <router-link v-else to="/login">登录</router-link>
      </nav>
    </div>
  </header>
  <main class="page">
    <router-view />
  </main>
  <div class="toast-host" aria-live="polite">
    <div
      v-for="t in toasts"
      :key="t.id"
      class="toast"
      :class="t.kind === 'ticket' ? 'ticket' : t.kind"
      @click="remove(t.id)"
    >
      <template v-if="t.kind === 'ticket'">
        <span class="seal seal-red">{{ t.stamp }}</span>
        <div>
          <b>{{ t.title }}</b>
          <p>{{ t.detail }}</p>
        </div>
      </template>
      <template v-else>
        <b>{{ t.title }}</b>
        <p v-if="t.detail">{{ t.detail }}</p>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import toasts, { remove } from './toast'

// localStorage 非响应式，简单场景下用整页刷新兜底（登录/退出后 location.href 跳转）
const logged = computed(() => !!localStorage.getItem('token'))

function logout() {
  localStorage.removeItem('token')
  window.location.href = '/login'
}
</script>
