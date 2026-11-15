import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App'

// 单页入口；全局状态与 SSE 会话都在 App 内，StrictMode 下会额外做一次开发态挂载检查。
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
