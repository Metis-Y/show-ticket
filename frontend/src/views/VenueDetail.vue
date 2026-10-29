<template>
  <div class="page-inner" v-if="venue">
    <section class="ticket masthead">
      <div class="stub">
        <span class="stub-vert">{{ venue.area }}</span>
      </div>
      <div class="tear"></div>
      <div class="ticket-main">
        <img v-if="venue.images" class="hero-photo" :src="firstImg(venue.images)" :alt="venue.name" />
        <h1 class="page-title">{{ venue.name }}</h1>
        <p class="addr">{{ venue.area }} {{ venue.address }}</p>
        <dl class="meta">
          <div>
            <dt>营业时间</dt>
            <dd>{{ venue.openHours }}</dd>
          </div>
          <div>
            <dt>评分</dt>
            <dd>{{ (venue.score / 10).toFixed(1) }} 分</dd>
          </div>
          <div>
            <dt>均价</dt>
            <dd><span class="price">¥{{ yuan(venue.avgPrice) }}</span></dd>
          </div>
          <div>
            <dt>看过</dt>
            <dd>{{ uv }} 人</dd>
          </div>
        </dl>
      </div>
    </section>

    <h2 class="section-title">在售演出</h2>
    <div v-if="shows.length === 0" class="empty">这个场馆暂时没有在售演出，去别的场馆看看</div>
    <article v-for="s in shows" :key="s.id" class="ticket">
      <div class="stub">
        <span class="stub-vert">副券</span>
        <span class="stub-price">¥{{ yuan(s.payValue) }}</span>
      </div>
      <div class="tear"></div>
      <div class="ticket-main">
        <h3 class="item-title">{{ s.title }}</h3>
        <p class="addr">{{ s.subTitle }}</p>
        <dl class="meta">
          <div>
            <dt>原价</dt>
            <dd><span class="origin">¥{{ yuan(s.actualValue) }}</span></dd>
          </div>
          <div>
            <dt>库存</dt>
            <dd>{{ s.stock ?? '—' }} 张</dd>
          </div>
          <div v-if="s.beginTime">
            <dt>售票</dt>
            <dd>{{ fmt(s.beginTime) }} 起</dd>
          </div>
        </dl>
        <p v-if="s.rules" class="rules">{{ s.rules }}</p>
        <div class="btn-row">
          <button v-if="saleState(s).ok" class="btn btn-primary" @click="grab(s.id)">立即抢票</button>
          <button v-else class="btn btn-ghost" disabled>{{ saleState(s).text }}</button>
        </div>
      </div>
    </article>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import request from '../api'
import { toast, ticketToast } from '../toast'

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

function yuan(v) {
  return (v / 100).toFixed(0)
}

// 场馆图片支持逗号分隔多张，取第一张
function firstImg(images) {
  return String(images).split(',')[0]
}

// 抢票按钮的三种真实状态：未开售 / 已售罄 / 在售
function saleState(s) {
  const now = Date.now()
  if (s.stock === 0) return { ok: false, text: '已售罄' }
  if (s.beginTime && new Date(s.beginTime).getTime() > now) return { ok: false, text: fmt(s.beginTime) + ' 开售' }
  if (s.endTime && new Date(s.endTime).getTime() < now) return { ok: false, text: '已停售' }
  return { ok: true, text: '立即抢票' }
}

async function grab(showId) {
  if (!localStorage.getItem('token')) {
    toast('请先登录后再抢票')
    router.push('/login')
    return
  }
  const orderId = await request.post('/ticket-order/grab/' + showId)
  ticketToast(orderId)
}
</script>
