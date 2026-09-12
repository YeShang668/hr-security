import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发服务器：端口 5173，/api 反向代理到后端 8080。
// 走 dev proxy 而不是后端开 CORS 的原因（面试点）：
// 浏览器视角下前后端同源，没有预检请求，也不需要在后端放宽 CORS；
// 生产用 Nginx 做同样的同源转发，开发与生产形态一致。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
