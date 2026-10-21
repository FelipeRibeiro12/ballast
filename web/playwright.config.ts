import { defineConfig, devices } from '@playwright/test'

// O endereço vem do scripts/e2e.sh, que sobe o web descartável numa porta alta.
// Sem ele o teste não roda: um padrão aqui apontaria para o `make dev` de alguém.
const baseURL = process.env.E2E_BASE_URL
if (baseURL === undefined || baseURL === '') {
  throw new Error('Defina E2E_BASE_URL. Use `make e2e`, que sobe o ambiente descartável.')
}

export default defineConfig({
  testDir: './e2e',
  // O fluxo cria usuário novo a cada execução, mas os projetos dividem o mesmo banco: em série.
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    browserName: 'chromium',
    // No CI o relatório vira artifact baixável, e o trace levaria o corpo do cadastro e o cookie.
    trace: process.env.CI ? 'off' : 'retain-on-failure',
    // O service worker do PWA cachearia respostas entre os projetos e esconderia erro de proxy.
    serviceWorkers: 'block',
    locale: 'pt-BR',
    timezoneId: 'America/Sao_Paulo',
  },
  projects: [
    {
      name: 'celular',
      use: { ...devices['Desktop Chrome'], viewport: { width: 360, height: 780 } },
    },
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } },
    },
  ],
})
