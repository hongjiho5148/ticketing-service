import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // 일부러 고정했다. 게이트웨이의 CORS 허용 목록이 정확히 이 오리진을 이름으로 적어 두고, 공개 사이트(5173)와 오리진이 다르다는 것이
  // 핵심이다. 그래야 두 앱의 localStorage가 분리된다.
  server: { port: 5174, strictPort: true },
})
