import type { VitePWAOptions } from 'vite-plugin-pwa'
import { padraoDoProxy } from './rotasDaApi.ts'

// Fica fora do vite.config.ts para o teste importar sem subir o Vite.
export const opcoesDoPwa: Partial<VitePWAOptions> = {
  // 'prompt' porque trocar a versão sozinho perderia um formulário em andamento (ADR-030).
  registerType: 'prompt',
  strategies: 'generateSW',
  // Service worker no dev cacheia asset e atrapalha o hot reload.
  devOptions: { enabled: false },
  manifest: {
    name: 'Ballast',
    short_name: 'Ballast',
    description: 'Controle financeiro pessoal com fiat e cripto no mesmo lugar.',
    lang: 'pt-BR',
    start_url: '/',
    scope: '/',
    display: 'standalone',
    theme_color: '#3b4cca',
    background_color: '#ffffff',
    icons: [
      { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
      { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
      { src: 'maskable-icon-512x512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  },
  // Sem runtimeCaching de propósito: a API nunca entra em cache, o saldo mostrado é o do servidor.
  workbox: {
    globPatterns: ['**/*.{html,js,css,svg,png,webmanifest}'],
    navigateFallback: '/index.html',
    // Mesma lista do proxy, para uma rota da API nunca virar o index.html.
    navigateFallbackDenylist: [new RegExp(padraoDoProxy)],
  },
}
