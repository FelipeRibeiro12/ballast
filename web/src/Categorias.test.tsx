import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import Categorias from './Categorias'

const categoria = (sobra: Record<string, unknown> = {}) => ({
  id: 'k1',
  nome: 'Mercado',
  tipo: 'despesa',
  paiId: null,
  cor: null,
  icone: null,
  protegida: false,
  ...sobra,
})

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

function mockFetch(): void {
  let lista = [categoria(), categoria({ id: 'k2', nome: 'Outros', protegida: true })]
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const metodo = init?.method ?? 'GET'
      if (url === '/categorias' && metodo === 'GET') return Promise.resolve(jsonResponse(lista, 200))
      if (url === '/categorias' && metodo === 'POST') {
        const nova = categoria({ id: 'k3', nome: 'Pets' })
        lista = [...lista, nova]
        return Promise.resolve(jsonResponse(nova, 201))
      }
      if (url === '/categorias/k1' && metodo === 'DELETE') {
        lista = lista.filter((c) => c.id !== 'k1')
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      return Promise.resolve(jsonResponse({ erro: 'inesperado' }, 500))
    }),
  )
}

describe('Categorias', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('avisa a aplicação quando cria uma categoria', async () => {
    mockFetch()
    const aoMudar = vi.fn()
    render(<Categorias aoSessaoExpirar={vi.fn()} aoMudar={aoMudar} />)

    fireEvent.click(await screen.findByRole('button', { name: /nova categoria/i }))
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Pets' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    await waitFor(() => expect(aoMudar).toHaveBeenCalledTimes(1))
    expect(await screen.findByText('Pets')).toBeVisible()
  })

  it('avisa a aplicação quando apaga uma categoria', async () => {
    mockFetch()
    const aoMudar = vi.fn()
    render(<Categorias aoSessaoExpirar={vi.fn()} aoMudar={aoMudar} />)

    fireEvent.click(await screen.findByRole('button', { name: 'Mais ações de Mercado' }))
    fireEvent.click(screen.getByRole('button', { name: 'Apagar Mercado' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar apagar Mercado' }))

    await waitFor(() => expect(aoMudar).toHaveBeenCalledTimes(1))
  })

  it('não oferece ações nas categorias protegidas', async () => {
    mockFetch()
    render(<Categorias aoSessaoExpirar={vi.fn()} aoMudar={vi.fn()} />)

    const outros = (await screen.findByText('Outros')).closest('li')
    expect(outros).not.toBeNull()
    expect(within(outros as HTMLElement).queryByRole('button')).toBeNull()
  })
})
