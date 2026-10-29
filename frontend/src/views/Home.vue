<template>
  <div class="page-inner">
    <section class="ticket masthead">
      <div class="stub">
        <span class="stub-vert">上海</span>
        <span class="stub-date">{{ stubDate }}</span>
      </div>
      <div class="tear"></div>
      <div class="ticket-main">
        <p class="masthead-date">今天是 {{ todayText }}</p>
        <h1 class="display">{{ activeTypeName }}场馆</h1>
      </div>
    </section>

    <div class="toolbar">
      <div class="tablist">
        <button
          v-for="t in types"
          :key="t.id"
          :class="{ active: t.id === activeType }"
          @click="switchType(t.id)"
        ><img v-if="t.icon" class="tab-icon" :src="t.icon" alt="" />{{ t.name }}</button>
      </div>
      <button class="btn btn-ghost" @click="useNearby">{{ nearby ? '按距离排序 · 5 公里内' : '按距离排序' }}</button>
    </div>

    <div v-if="venues.length === 0" class="empty">这一页没有场馆了，回上一页或换个分类看看</div>
    <article v-for="v in venues" :key="v.id" class="ticket clickable" @click="$router.push('/venue/' + v.id)">
      <div class="stub">
        <span class="stub-vert">{{ v.area }}</span>
      </div>
      <div class="tear"></div>
      <div class="ticket-main">
        <div class="venue-row">
          <img v-if="v.images" class="venue-photo" :src="firstImg(v.images)" :alt="v.name" />
          <div class="venue-info">
            <h2 class="item-title">{{ v.name }}</h2>
            <p class="addr">{{ v.address }}</p>
          </div>
        </div>
        <dl class="meta">
          <div>
            <dt>均价</dt>
            <dd><span class="price">¥{{ yuan(v.avgPrice) }}</span></dd>
          </div>
          <div>
            <dt>评分</dt>
            <dd>{{ (v.score / 10).toFixed(1) }} 分</dd>
          </div>
          <div>
            <dt>已售</dt>
            <dd>{{ (v.sold || 0).toLocaleString() }} 张</dd>
          </div>
          <div v-if="v.distance != null">
            <dt>距离</dt>
            <dd>{{ distText(v.distance) }}</dd>
          </div>
        </dl>
      </div>
    </article>

    <div class="pager">
      <button class="btn btn-ghost" :disabled="current <= 1" @click="page(-1)">上一页</button>
      <span>第 {{ current }} 页</span>
      <button class="btn btn-ghost" :disabled="venues.length === 0" @click="page(1)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import request from '../api'
import { toast } from '../toast'

const types = ref([])
const activeType = ref(1)
const venues = ref([])
const current = ref(1)
const nearby = ref(false)
const pos = ref(null)

const now = new Date()
const todayText = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日`
const stubDate = `${now.getMonth() + 1}/${now.getDate()}`

const activeTypeName = computed(() => {
  const t = types.value.find((x) => x.id === activeType.value)
  return t ? t.name : '演出'
})

onMounted(async () => {
  types.value = await request.get('/show-type/list')
  await load()
})

async function load() {
  const params = { typeId: activeType.value, current: current.value }
  if (nearby.value && pos.value) {
    params.x = pos.value.x
    params.y = pos.value.y
  }
  venues.value = await request.get('/venue/of/type', { params }) || []
}

function switchType(id) {
  activeType.value = id
  current.value = 1
  load()
}

function page(d) {
  current.value += d
  load()
}

// 浏览器定位 -> 带 x/y 查询，走后端 Redis GEO，5km 内按距离排序
function useNearby() {
  if (!navigator.geolocation) return toast('当前浏览器不支持定位，可以直接浏览列表', 'err')
  navigator.geolocation.getCurrentPosition(
    (p) => {
      pos.value = { x: p.coords.longitude, y: p.coords.latitude }
      nearby.value = true
      current.value = 1
      load()
    },
    () => toast('定位失败：种子场馆在上海，可把坐标定位到上海再试，或直接浏览列表', 'err')
  )
}

function yuan(v) {
  return (v / 100).toFixed(0)
}

// 场馆图片支持逗号分隔多张，取第一张
function firstImg(images) {
  return String(images).split(',')[0]
}

function distText(d) {
  return d < 1000 ? `${Math.round(d)} 米` : `${(d / 1000).toFixed(1)} 公里`
}
</script>
