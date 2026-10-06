import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // Fixed on purpose: the gateway's CORS allow-list names this exact origin, and a different
  // origin from the public site (5173) is the whole point - it keeps the two apps' localStorage apart.
  server: { port: 5174, strictPort: true },
})
