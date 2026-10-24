import { fireEvent, render, screen, within } from '@testing-library/react'
import App from './App'

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

const usuario = { id: '7f1e2c9a-0b6d-4f43-9a52-1c8d3e5b6a70', email: 'ana@exemplo.com', nome: 'Ana' }

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

describe('Topo: tema', () => {
  beforeEach(() => {
    window.location.hash = '#/inicio'
    mockFetch()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    window.localStorage.clear()
    delete document.documentElement.dataset.tema
  })

  it('oferece Claro, Escuro e Sistema, com o do sistema marcado de início', async () => {
    render(<App />)
    const grupo = within(await screen.findByRole('group', { name: 'Tema' }))
    expect(grupo.getByRole('button', { name: 'Tema claro' })).toHaveAttribute('aria-pressed', 'false')
    expect(grupo.getByRole('button', { name: 'Tema escuro' })).toHaveAttribute('aria-pressed', 'false')
    expect(grupo.getByRole('button', { name: 'Tema sistema' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('escolher o escuro marca o documento, guarda a escolha e marca o botão', async () => {
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Tema escuro' }))
    expect(document.documentElement.dataset.tema).toBe('escuro')
    expect(window.localStorage.getItem('ballast.tema')).toBe('escuro')
    expect(screen.getByRole('button', { name: 'Tema escuro' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Tema sistema' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('voltar ao sistema tira a marca do documento', async () => {
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Tema claro' }))
    expect(document.documentElement.dataset.tema).toBe('claro')
    fireEvent.click(screen.getByRole('button', { name: 'Tema sistema' }))
    expect(document.documentElement.dataset.tema).toBeUndefined()
  })

  it('lê a escolha guardada na abertura', async () => {
    window.localStorage.setItem('ballast.tema', 'escuro')
    render(<App />)
    expect(await screen.findByRole('button', { name: 'Tema escuro' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('segue funcionando quando o armazenamento lança', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('cheio', 'QuotaExceededError')
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Tema escuro' }))
    expect(document.documentElement.dataset.tema).toBe('escuro')
    vi.restoreAllMocks()
  })
})

describe('Topo: segurança', () => {
  beforeEach(() => {
    window.location.hash = '#/inicio'
    mockFetch()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    window.location.hash = ''
  })

  it('tem o link para a tela Segurança, fora da barra de seções', async () => {
    render(<App />)
    const link = await screen.findByRole('link', { name: 'Segurança' })
    expect(link).toHaveAttribute('href', '#/seguranca')
    const nav = screen.getByRole('navigation', { name: 'Seções' })
    expect(within(nav).queryByRole('link', { name: 'Segurança' })).toBeNull()
  })

  it('marca o link na tela Segurança', async () => {
    window.location.hash = '#/seguranca'
    render(<App />)
    expect(await screen.findByRole('link', { name: 'Segurança' })).toHaveAttribute('aria-current', 'page')
    expect(await screen.findByRole('heading', { level: 2, name: 'Segurança' })).toBeVisible()
  })
})
