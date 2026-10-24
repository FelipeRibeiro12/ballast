/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'
import { configDefaults } from 'vitest/config'
import { opcoesDoPwa } from './src/pwa.ts'
import { PREFIXO_DA_API, conferirOrigemDeProducao, padraoDoPrefixo, padraoDoProxy } from './src/rotasDaApi.ts'

// API_PROXY_TARGET só existe para rodar a API em outra porta localmente.
const apiTarget = process.env.API_PROXY_TARGET ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  // O build de produção para aqui se a API ficar em outra origem: o cookie SameSite=Lax não iria
  // junto (ADR-020, ADR-044). Vale também para VITE_API_URL posta no painel do Vercel.
  if (mode === 'production') {
    conferirOrigemDeProducao({ VITE_API_URL: loadEnv(mode, process.cwd(), 'VITE_').VITE_API_URL })
  }
  return {
    plugins: [react(), VitePWA(opcoesDoPwa)],
    server: {
      // Evita CORS no dev: o Vite repassa à API todo segmento que o openapi.yaml tem; a lista fica em rotasDaApi.ts.
      // O build de produção chama /api, como no Vercel; o vite preview do e2e tira o prefixo igual.
      proxy: {
        [padraoDoProxy]: apiTarget,
        [padraoDoPrefixo]: {
          target: apiTarget,
          rewrite: (caminho: string) => caminho.slice(PREFIXO_DA_API.length) || '/',
        },
      },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: './src/setupTests.ts',
      // e2e/ é do Playwright; o vitest tentaria rodar o *.spec.ts e falharia no import.
      exclude: [...configDefaults.exclude, 'e2e/**'],
    },
  }
})
