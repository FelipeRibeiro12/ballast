import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import Orcamento from './Orcamento'
import { acao } from './testeAcoes'

function categoria(id: string, nome: string, tipo: 'receita' | 'despesa'): Record<string, unknown> {
  return { id, nome, tipo, paiId: null, cor: null, icone: null }
}

const categorias = [
  categoria('cd1', 'Mercado', 'despesa'),
  categoria('cd2', 'Lazer', 'despesa'),
  categoria('cr1', 'Salário', 'receita'),
]

const outubro = {
  mes: '2026-10',
  orcamentos: [
    {
      categoriaId: 'cd1',
      categoriaNome: 'Mercado',
      tetoCentavos: 80_000,
      desde: '2026-09',
      gastoCentavos: 30_000,
      previstoCentavos: 9_000,
      restanteCentavos: 50_000,
    },
  ],
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

type Rotas = Record<string, (corpo: unknown) => Promise<Response>>

const responde = (corpo: unknown, status = 200) => () => Promise.resolve(jsonResponse(corpo, status))
const semCorpo = () => Promise.resolve(new Response(null, { status: 204 }))

// Roteia por "MÉTODO caminho"; o que não estiver em rotas responde com o mês de outubro.
function mockFetch(rotas: Rotas = {}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const corpo: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      const rota = rotas[chave]
      if (rota) return rota(corpo)
      if (chave === 'GET /categorias') return Promise.resolve(jsonResponse(categorias, 200))
      if (chave.startsWith('GET /orcamentos?')) {
        return Promise.resolve(jsonResponse({ mes: url.slice(-7), orcamentos: url.endsWith('2026-10') ? outubro.orcamentos : [] }, 200))
      }
      return Promise.reject(new Error(`rota sem mock: ${chave}`))
    }),
  )
}

function chamadas(): string[] {
  return vi.mocked(fetch).mock.calls.map(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}`)
}

function corpoDe(chave: string): unknown {
  const chamada = vi.mocked(fetch).mock.calls.find(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}` === chave)
  const corpo = chamada?.[1]?.body
  return typeof corpo === 'string' ? JSON.parse(corpo) : undefined
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function renderizar() {
  const aoSessaoExpirar = vi.fn()
  render(<Orcamento aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-22T12:00:00-03:00'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Orcamento', () => {
  it('mostra o teto do mês com o gasto, o que falta e o previsto', async () => {
    mockFetch()
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Orçamentos' })
    const [mercado] = within(lista).getAllByRole('listitem')
    expect(within(mercado).getByText('Mercado')).toBeVisible()
    expect(within(mercado).getByText(texto('R$ 300,00 de R$ 800,00'))).toBeVisible()
    expect(within(mercado).getByText(texto('Falta R$ 500,00'))).toBeVisible()
    expect(within(mercado).getByText(texto('Mais R$ 90,00 previstos no mês'))).toBeVisible()
    expect(within(mercado).getByText('Teto desde setembro de 2026')).toBeVisible()
    expect(chamadas()).toContain('GET /orcamentos?mes=2026-10')
  })

  it('avisa quando o gasto passou do teto', async () => {
    const passou = {
      ...outubro,
      orcamentos: [{ ...outubro.orcamentos[0], gastoCentavos: 85_000, previstoCentavos: 0, restanteCentavos: -5_000, desde: '2026-10' }],
    }
    mockFetch({ 'GET /orcamentos?mes=2026-10': responde(passou) })
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Orçamentos' })
    expect(within(lista).getByText(texto('Passou R$ 50,00 do teto'))).toBeVisible()
    expect(within(lista).getByText('Acima do teto')).toBeVisible()
    expect(within(lista).queryByText(/previstos/)).toBeNull()
    expect(within(lista).queryByText(/Teto desde/)).toBeNull()
  })

  it('troca de mês pelas setas', async () => {
    mockFetch()
    renderizar()
    await screen.findByRole('list', { name: 'Orçamentos' })

    fireEvent.click(screen.getByRole('button', { name: 'Próximo mês' }))

    expect(await screen.findByText('Nenhum orçamento neste mês.')).toBeVisible()
    expect(chamadas()).toContain('GET /orcamentos?mes=2026-11')
  })

  it('cria um teto para uma categoria de despesa sem teto no mês', async () => {
    mockFetch({ 'PUT /orcamentos/cd2': semCorpo })
    renderizar()
    await screen.findByRole('list', { name: 'Orçamentos' })

    fireEvent.click(screen.getByRole('button', { name: 'Novo teto' }))
    const form = screen.getByRole('form', { name: 'Teto do orçamento' })
    const opcoes = within(form)
      .getAllByRole('option')
      .map((opcao) => opcao.textContent)
    // Mercado já tem teto e Salário é receita.
    expect(opcoes).toEqual(['Lazer'])
    fireEvent.change(within(form).getByLabelText('Teto por mês'), { target: { value: '500,00' } })
    expect(within(form).getByText('Vale a partir de outubro de 2026, até você mudar.')).toBeVisible()
    fireEvent.click(within(form).getByRole('button', { name: 'Salvar teto' }))

    await waitFor(() => expect(chamadas()).toContain('PUT /orcamentos/cd2'))
    expect(corpoDe('PUT /orcamentos/cd2')).toEqual({ tetoCentavos: 50_000, aPartirDe: '2026-10' })
    await waitFor(() => expect(chamadas().filter((c) => c === 'GET /orcamentos?mes=2026-10')).toHaveLength(2))
  })

  it('edita o teto a partir do mês que está na tela', async () => {
    mockFetch({ 'PUT /orcamentos/cd1': semCorpo })
    renderizar()
    await screen.findByRole('list', { name: 'Orçamentos' })

    fireEvent.click(await acao('Editar teto de Mercado'))
    const form = screen.getByRole('form', { name: 'Teto do orçamento' })
    expect(within(form).getByLabelText('Teto por mês')).toHaveValue('800,00')
    fireEvent.change(within(form).getByLabelText('Teto por mês'), { target: { value: '900' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Salvar teto' }))

    await waitFor(() => expect(corpoDe('PUT /orcamentos/cd1')).toEqual({ tetoCentavos: 90_000, aPartirDe: '2026-10' }))
  })

  it('recusa valor fora do formato sem chamar a API', async () => {
    mockFetch()
    renderizar()
    await screen.findByRole('list', { name: 'Orçamentos' })

    fireEvent.click(screen.getByRole('button', { name: 'Novo teto' }))
    const form = screen.getByRole('form', { name: 'Teto do orçamento' })
    fireEvent.change(within(form).getByLabelText('Teto por mês'), { target: { value: 'abc' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Salvar teto' }))

    expect(within(form).getByRole('alert')).toHaveTextContent('Valor inválido. Use o formato 1.234,56.')
    expect(chamadas().some((c) => c.startsWith('PUT'))).toBe(false)
  })

  it('mostra a recusa do servidor', async () => {
    mockFetch({ 'PUT /orcamentos/cd2': responde({ erro: 'orçamento só vale para categoria de despesa' }, 400) })
    renderizar()
    await screen.findByRole('list', { name: 'Orçamentos' })

    fireEvent.click(screen.getByRole('button', { name: 'Novo teto' }))
    const form = screen.getByRole('form', { name: 'Teto do orçamento' })
    fireEvent.change(within(form).getByLabelText('Teto por mês'), { target: { value: '10' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Salvar teto' }))

    expect(await within(form).findByRole('alert')).toHaveTextContent('orçamento só vale para categoria de despesa')
  })

  it('encerra o orçamento a partir do mês em dois cliques', async () => {
    mockFetch({ 'DELETE /orcamentos/cd1?aPartirDe=2026-10': semCorpo })
    renderizar()
    await screen.findByRole('list', { name: 'Orçamentos' })

    fireEvent.click(await acao('Encerrar orçamento de Mercado'))
    expect(chamadas().some((c) => c.startsWith('DELETE'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar encerrar Mercado a partir de outubro de 2026' }))

    await waitFor(() => expect(chamadas()).toContain('DELETE /orcamentos/cd1?aPartirDe=2026-10'))
  })

  it('avisa quem perdeu a sessão', async () => {
    mockFetch({ 'GET /orcamentos?mes=2026-10': responde({ erro: 'não autenticado' }, 401) })
    const { aoSessaoExpirar } = renderizar()

    await waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })

  it('mostra o erro de leitura', async () => {
    mockFetch({ 'GET /orcamentos?mes=2026-10': () => Promise.resolve(new Response(null, { status: 503 })) })
    renderizar()

    expect(await screen.findByRole('alert')).toHaveTextContent('Serviço indisponível. Tente de novo em instantes.')
  })
})
