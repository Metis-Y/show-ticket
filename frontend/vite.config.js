import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发模式：vite dev server 把 /api 代理到本机后端 8081
// 生产模式：构建产物由 nginx 托管，nginx 负责 /api 反向代理
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      }
    }
  }
})
