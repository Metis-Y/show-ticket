<template>
  <div class="page">
    <div class="tabs">
      <span v-for="t in types" :key="t.id" :class="{ active: t.id === activeType }" @click="switchType(t.id)">{{ t.name }}</span>
      <button class="near" @click="useNearby">{{ nearby ? '按距离排序中(5km)' : '按距离排序' }}</button>
    </div>

    <div v-if="venues.length === 0" class="empty">这一页没有场馆了</div>
    <div v-for="v in venues" :key="v.id" class="card clickable" @click="$router.push('/venue/' + v.id)">
      <div class="card-head">
        <b>{{ v.name }}</b>
        <span class="score">{{ (v.score / 10).toFixed(1) }} 分</span>
      </div>
      <div class="line">{{ v.area }} · {{ v.address }}</div>
      <div class="line">均价 ¥{{ (v.avgPrice / 100).toFixed(0) }} · 已售 {{ v.sold }}</div>
      <div v-if="v.distance != null" class="line dist">距你 {{ Math.round(v.distance) }} 米</div>
    </div>

    <div class="pager">
      <button :disabled="current <= 1" @click="page(-1)">上一页</button>
      <span>第 {{ current }} 页</span>
      <button :disabled="venues.length === 0" @click="page(1)">下一页</button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import request from '../api'

const types = ref([])
const activeType = ref(1)
const venues = ref([])
const current = ref(1)
const nearby = ref(false)
const pos = ref(null)

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
  if (!navigator.geolocation) return alert('浏览器不支持定位')
  navigator.geolocation.getCurrentPosition(
    (p) => {
      pos.value = { x: p.coords.longitude, y: p.coords.latitude }
      nearby.value = true
      current.value = 1
      load()
    },
    () => alert('定位失败：种子场馆在上海，可手动把坐标定位到上海再试，或直接浏览列表')
  )
}
</script>
