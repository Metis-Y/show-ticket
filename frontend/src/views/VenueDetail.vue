<template>
  <div class="page" v-if="venue">
    <div class="card">
      <h2>{{ venue.name }}</h2>
      <div class="line">{{ venue.area }} · {{ venue.address }} · 营业 {{ venue.openHours }}</div>
      <div class="line">评分 {{ (venue.score / 10).toFixed(1) }} · 均价 ¥{{ (venue.avgPrice / 100).toFixed(0) }} · 本页 UV {{ uv }}</div>
    </div>

    <h3>在售演出</h3>
    <div v-if="shows.length === 0" class="empty">暂无在售演出</div>
    <div v-for="s in shows" :key="s.id" class="card">
      <div class="card-head"><b>{{ s.title }}</b></div>
      <div class="line">{{ s.subTitle }}</div>
      <div class="line">
        <span class="price">¥{{ (s.payValue / 100).toFixed(0) }}</span>
        <span class="origin">¥{{ (s.actualValue / 100).toFixed(0) }}</span>
        · 库存 {{ s.stock ?? '—' }}
      </div>
      <div class="line" v-if="s.beginTime">售票 {{ fmt(s.beginTime) }} ~ {{ fmt(s.endTime) }}</div>
      <button class="primary" @click="grab(s.id)">立即抢票</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '../api'

const route = useRoute()
const router = useRouter()
const venue = ref(null)
const uv = ref(0)
const shows = ref([])

onMounted(async () => {
  const id = route.params.id
  venue.value = await request.get('/venue/' + id)
  // 打开详情页即记录一次 UV（HyperLogLog）
  uv.value = await request.get('/venue/uv/' + id)
  shows.value = await request.get('/show/list/' + id) || []
})

function fmt(t) {
  return String(t || '').replace('T', ' ').slice(0, 16)
}

async function grab(showId) {
  if (!localStorage.getItem('token')) {
    alert('请先登录')
    router.push('/login')
    return
  }
  const orderId = await request.post('/ticket-order/grab/' + showId)
  alert('抢票成功！订单号：' + orderId + '\n\n订单正在通过 MQ 异步落库，未支付订单 60 秒后自动取消。\n到「我的订单」页可以实时观察状态变化。')
}
</script>
