import { render, screen, waitFor } from '@testing-library/react'
import App from './App'

const usuario = { id: '7f1e2c9a-0b6d-4f43-9a52-1c8d3e5b6a70', email: 'ana@exemplo.com', nome: 'Ana' }

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

function mockFetch(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url === '/auth/eu') return Promise.resolve(jsonResponse(usuario, 200))
      if (url === '/healthz') return Promise.resolve(jsonResponse({ mensagem: 'ok' }, 200))
      if (url === '/contas' || url === '/categorias' || url === '/recorrencias') {
        return Promise.resolve(jsonResponse([], 200))
      }
      if (url.startsWith('/lancamentos?')) return Promise.resolve(jsonResponse([], 200))
      if (url === '/lancamentos/vencidos') {
        return Promise.resolve(jsonResponse({ lancamentos: [], maisAlgum: false }, 200))
      }
      return new Promise<Response>(() => {})
    }),
  )
}

function chamadasAContas(): number {
  return vi.mocked(fetch).mock.calls.filter(([url]) => String(url) === '/contas').length
}

function mudarVisibilidade(estado: 'hidden' | 'visible'): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: estado })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('App: atualiza ao voltar', () => {
  beforeEach(() => {
    window.location.hash = '#/inicio'
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 19, 12, 0))
    mockFetch()
  })

  afterEach(() => {
    mudarVisibilidade('visible')
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('busca as contas de novo depois de um tempo em segundo plano', async () => {
    render(<App />)
    await screen.findByRole('heading', { name: 'Início' })
    await waitFor(() => expect(chamadasAContas()).toBeGreaterThan(0))
    const antes = chamadasAContas()

    mudarVisibilidade('hidden')
    vi.setSystemTime(new Date(2026, 9, 19, 12, 5))
    mudarVisibilidade('visible')

    await waitFor(() => expect(chamadasAContas()).toBeGreaterThan(antes))
  })

  it('não busca de novo numa saída curta', async () => {
    render(<App />)
    await screen.findByRole('heading', { name: 'Início' })
    await waitFor(() => expect(chamadasAContas()).toBeGreaterThan(0))
    const antes = chamadasAContas()

    mudarVisibilidade('hidden')
    vi.setSystemTime(new Date(2026, 9, 19, 12, 0, 5))
    mudarVisibilidade('visible')
    await new Promise((resolve) => setTimeout(resolve, 50))

    expect(chamadasAContas()).toBe(antes)
  })
})
