import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'
import tailwindcss from '@tailwindcss/vite'

// During `npm run dev`, the UI is served by Vite but the Nebula server runs
// separately (default port 8099). We proxy the API + websockets to it so the
// browser only ever talks to a single origin (same as in production, where the
// Nebula server serves these files directly).
const NEBULA_SERVER = process.env.NEBULA_SERVER ?? 'http://localhost:8099'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/api': { target: NEBULA_SERVER, changeOrigin: true, ws: true },
      '/health': { target: NEBULA_SERVER, changeOrigin: true },
    },
  },
})
