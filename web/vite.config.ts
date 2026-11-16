import { defineConfig } from 'vite'

// Dev server with HMR: /api is proxied to the local loot-radar backend.
// 后端端口被占用时会自动顺延，用 LOOTRADAR_PORT 指定实际端口避免代理落空。
// 不依赖 @types/node，直接从 globalThis 读取 Node 的 process.env。
const env = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process?.env
const apiPort = Number(env?.LOOTRADAR_PORT ?? 8765)

export default defineConfig({
  server: {
    // 固定 IPv4：Windows 上默认 localhost 可能只监听 ::1，导致 127.0.0.1:5173 打不开。
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      '/api': `http://127.0.0.1:${Number.isFinite(apiPort) ? apiPort : 8765}`,
    },
  },
})
