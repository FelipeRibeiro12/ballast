/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { padraoDoProxy } from './src/rotasDaApi.ts'

// API_PROXY_TARGET só existe para rodar a API em outra porta localmente.
const apiTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Evita CORS no dev: o Vite repassa à API todo segmento que o openapi.yaml tem; a lista fica em rotasDaApi.ts.
    proxy: {
      [padraoDoProxy]: apiTarget,
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/setupTests.ts',
  },
})
