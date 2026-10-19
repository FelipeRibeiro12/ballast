/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// API_PROXY_TARGET só existe para rodar a API em outra porta localmente.
const apiTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Evita CORS no dev: o browser chama a própria origem e o Vite repassa à API.
    proxy: {
      '/healthz': apiTarget,
      '/auth': apiTarget,
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/setupTests.ts',
  },
})
