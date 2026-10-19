import { render, screen } from '@testing-library/react'
import App from './App'

// Recebe uma função para a promessa rejeitada só nascer quando o fetch for chamado.
function mockFetch(resposta: () => Promise<Response>): void {
  vi.stubGlobal('fetch', vi.fn(resposta))
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('mostra o título Ballast e o estado de carregando', () => {
    mockFetch(() => new Promise<Response>(() => {}))
    render(<App />)
    expect(screen.getByRole('heading', { name: /ballast/i })).toBeVisible()
    expect(screen.getByText(/carregando/i)).toBeVisible()
  })

  it('mostra a mensagem lida do banco quando a API responde 200', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ mensagem: 'ok' }, 200)))
    render(<App />)
    expect(await screen.findByText('ok')).toBeVisible()
    expect(fetch).toHaveBeenCalledWith('/healthz')
  })

  it('mostra erro genérico quando a API responde 503', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ erro: 'indisponível' }, 503)))
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível/i)
  })

  it('mostra erro genérico quando a rede está fora', async () => {
    mockFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível/i)
  })

  it('trata resposta 200 fora do formato como erro', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ outra: 1 }, 200)))
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível/i)
  })
})
