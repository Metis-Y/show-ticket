import axios from 'axios'

// 统一请求出口：
// - 自动带上登录 token（后端读的是 authorization 头）
// - 后端返回体 { success, data, errorMsg }，这里直接拆出 data
// - 失败统一弹窗，业务代码只处理成功路径
const request = axios.create({ baseURL: '/api', timeout: 10000 })

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.authorization = token
  return config
})

request.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body.success) return body.data
    alert(body.errorMsg || '请求失败')
    return Promise.reject(new Error(body.errorMsg))
  },
  (err) => {
    alert('网络异常：' + err.message)
    return Promise.reject(err)
  }
)

export default request
