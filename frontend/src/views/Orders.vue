<template>
  <div class="page-inner">
    <h1 class="page-title">我的订单</h1>
    <p class="note">未支付订单 60 秒后由 RabbitMQ 延迟消息自动关单并回补库存。本页每 5 秒刷新，停在这里就能看到状态变化。</p>

    <div v-if="orders.length === 0" class="empty">还没有订单，去场馆页抢一张</div>
    <article v-for="o in orders" :key="o.id" class="ticket">
      <div class="stub">
        <span class="stub-vert">票根</span>
        <span class="stub-time">{{ hhmm(o.createTime) }}</span>
      </div>
      <div class="tear"></div>
      <div class="ticket-main">
        <div class="ticket-head">
          <h2 class="item-title">演出 #{{ o.showId }}</h2>
          <span class="seal" :class="sealClass(o.status)">{{ statusText(o.status) }}</span>
        </div>
        <p class="addr">订单 <span class="serial">{{ o.id }}</span></p>
        <dl class="meta">
          <div>
            <dt>下单</dt>
            <dd>{{ fmt(o.createTime) }}</dd>
          </div>
          <div v-if="o.payTime">
            <dt>支付</dt>
            <dd>{{ fmt(o.payTime) }}</dd>
          </div>
          <div v-if="o.status === 4 && o.updateTime">
            <dt>取消</dt>
            <dd>{{ fmt(o.updateTime) }}</dd>
          </div>
        </dl>
        <p v-if="o.status === 4" class="rules">超时未支付，库存已回补</p>
        <div class="btn-row" v-if="o.status === 1">
          <button class="btn btn-primary" @click="pay(o.id)">立即支付</button>
        </div>
      </div>
    </article>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import request from '../api'
import { toast } from '../toast'

const orders = ref([])
let timer = null

async function load() {
  orders.value = await request.get('/ticket-order/of/me') || []
}

function pay(id) {
  request.post('/ticket-order/pay/' + id).then(() => {
    toast('支付成功')
    load()
  })
}

function statusText(s) {
  return ({ 1: '未支付', 2: '已支付', 3: '已使用', 4: '已取消', 5: '退款中', 6: '已退款' })[s] || ('状态' + s)
}

// 未支付（红）> 已支付（绿）> 已完结（灰）
function sealClass(s) {
  if (s === 1 || s === 5) return 'seal-red'
  if (s === 2) return 'seal-green'
  return 'seal-grey'
}

function fmt(t) {
  return String(t || '').replace('T', ' ').slice(0, 19)
}

function hhmm(t) {
  return String(t || '').replace('T', ' ').slice(11, 16)
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
