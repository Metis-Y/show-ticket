import { reactive } from 'vue'

// 轻量提示：替代 alert，由 App.vue 的 toast-host 统一渲染
// kind: info（默认）/ error / ticket（抢票成功，带印章动画）
const toasts = reactive([])
let seq = 0

function push({ kind, title, detail, stamp }) {
  const id = ++seq
  toasts.push({ id, kind, title, detail, stamp })
  setTimeout(() => remove(id), kind === 'ticket' ? 6000 : 4000)
}

export function toast(message, kind = 'info') {
  const [title, ...rest] = String(message || '').split('\n')
  push({ kind, title, detail: rest.join('\n') })
}

// 抢票成功：一张小票根盖上「抢票成功」印章
export function ticketToast(orderId) {
  push({
    kind: 'ticket',
    stamp: '抢票成功',
    title: '订单 ' + orderId,
    detail: '60 秒内未支付会自动取消'
  })
}

export function remove(id) {
  const i = toasts.findIndex((t) => t.id === id)
  if (i > -1) toasts.splice(i, 1)
}

export default toasts
