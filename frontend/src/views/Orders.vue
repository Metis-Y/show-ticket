<template>
  <div class="page">
    <h2>我的订单</h2>
    <p class="tip">未支付订单 60 秒后由 RabbitMQ 延迟消息自动关单并回补库存；本页每 5 秒自动刷新，停在页面上就能看到 status 变化</p>
    <div v-if="orders.length === 0" class="empty">还没有订单，去抢一张吧</div>
    <div v-for="o in orders" :key="o.id" class="card">
      <div class="card-head">
        <b>订单 {{ o.id }}</b>
        <span :class="'st st' + o.status">{{ statusText(o.status) }}</span>
      </div>
      <div class="line">演出 #{{ o.showId }} · 下单 {{ fmt(o.createTime) }}</div>
      <div class="line" v-if="o.payTime">支付时间 {{ fmt(o.payTime) }}</div>
      <div class="line" v-if="o.status === 4 && o.updateTime">取消时间 {{ fmt(o.updateTime) }}（超时未支付，库存已回补）</div>
      <button v-if="o.status === 1" class="primary" @click="pay(o.id)">立即支付</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import request from '../api'

const orders = ref([])
let timer = null

async function load() {
  orders.value = await request.get('/ticket-order/of/me') || []
}

function pay(id) {
  request.post('/ticket-order/pay/' + id).then(load)
}

function statusText(s) {
  return ({ 1: '未支付', 2: '已支付', 3: '已使用', 4: '已取消', 5: '退款中', 6: '已退款' })[s] || ('状态' + s)
}

function fmt(t) {
  return String(t || '').replace('T', ' ').slice(0, 19)
}

onMounted(() => {
  if (!localStorage.getItem('token')) {
    window.location.href = '/login'
    return
  }
  load()
  timer = setInterval(load, 5000)
})

onUnmounted(() => clearInterval(timer))
</script>
