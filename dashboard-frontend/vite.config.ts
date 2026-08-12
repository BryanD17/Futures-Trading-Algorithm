import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
// The API target is overridable so the dashboard can be pointed at a
// throwaway backend on a spare port (V4 Agent 06 used this to gather Bot
// Chart evidence without disturbing an engine already bound to 8080).
// Unset — the normal case — it is the historical localhost:8080.
const API_TARGET = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: API_TARGET,
        changeOrigin: true
      },
      '/ws': {
        target: API_TARGET,
        changeOrigin: true,
        ws: true
      }
    }
  }
})
