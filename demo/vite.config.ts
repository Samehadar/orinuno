import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // Streaming proxy: pass selfHandleResponse=false (default) and rely on http-proxy
        // to forward chunks. Important for /providers/jutsu/stream which streams MP4 with
        // Range / 206 — buffering would break <video> seek and bloat dev-server memory.
        ws: true,
      },
    },
  },
})
