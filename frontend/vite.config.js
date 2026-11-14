import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev (npm run dev) the UI talks to the services on localhost; in Docker, nginx does the same proxying.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/ctrl': { target: 'http://localhost:8181', rewrite: (p) => p.replace(/^\/ctrl/, '') },
      '/net': { target: 'http://localhost:9191', rewrite: (p) => p.replace(/^\/net/, '') },
    },
  },
})
