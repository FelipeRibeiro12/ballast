import { fireEvent, render, screen, within } from '@testing-library/react'
import Relatorios from './Relatorios'

const outubro = {
  mes: '2026-10',
  totalDespesasCentavos: 120_000,
  despesasPorCategoria: [
    { categoriaId: 'cd1', categoriaNome: 'Mercado', gastoCentavos: 80_000 },
    { categoriaId: 'cd2', categoriaNome: 'Lazer', gastoCentavos: 39_500 },
    { categoriaId: 'cd3', categoriaNome: 'Assinatura', gastoCentavos: 500 },
  ],
  receitasEDespesas: [
    { mes: '2026-05', receitaCentavos: 0, despesaCentavos: 0 },
    { mes: '2026-06', receitaCentavos: 500_000, despesaCentavos: 90_000 },
    { mes: '2026-07', receitaCentavos: 500_000, despesaCentavos: 110_000 },
    { mes: '2026-08', receitaCentavos: 500_000, despesaCentavos: 95_000 },
    { mes: '2026-09', receitaCentavos: 100_000, despesaCentavos: 130_000 },
    { mes: '2026-10', receitaCentavos: 500_000, despesaCentavos: 120_000 },
  ],
}

function vazio(mes: string): unknown {
  return {
    mes,
    totalDespesasCentavos: 0,
    despesasPorCategoria: [],
    receitasEDespesas: outubro.receitasEDespesas.map((m) => ({ ...m, receitaCentavos: 0, despesaCentavos: 0 })),
  }
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

// Outubro tem dados; qualquer outro mês volta vazio. resposta troca só a do relatório do mês.
function mockFetch(resposta?: () => Promise<Response>): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.startsWith('/patrimonio/mensal?')) return Promise.resolve(jsonResponse({ mes: url.slice(-7), meses: [] }, 200))
      if (resposta) return resposta()
      if (url.startsWith('/relatorios?')) {
        const mes = url.slice(-7)
        return Promise.resolve(jsonResponse(mes === '2026-10' ? outubro : vazio(mes), 200))
      }
      return Promise.reject(new Error(`rota sem mock: ${url}`))
    }),
  )
}

function chamadas(): string[] {
  return vi.mocked(fetch).mock.calls.map(([url]) => String(url))
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function renderizar() {
  const aoSessaoExpirar = vi.fn()
  render(<Relatorios aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-22T12:00:00-03:00'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Relatorios', () => {
  it('mostra as despesas do mês por categoria com valor e parte do total', async () => {
    mockFetch()
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Despesas por categoria' })
    const [mercado, lazer, assinatura] = within(lista).getAllByRole('listitem')
    expect(within(mercado).getByText('Mercado')).toBeVisible()
    expect(within(mercado).getByText(texto('R$ 800,00'))).toBeVisible()
    expect(within(mercado).getByText('67%')).toBeVisible()
    expect(within(lazer).getByText('33%')).toBeVisible()
    // Parte que arredonda para zero não some: aparece como menos de 1%.
    expect(within(assinatura).getByText('<1%')).toBeVisible()
    const cartao = screen.getByRole('region', { name: 'Despesas por categoria' })
    expect(within(cartao).getByText(texto('R$ 1.200,00'))).toBeVisible()
    expect(chamadas()).toContain('/relatorios?mes=2026-10')
  })

  it('compara receitas e despesas dos seis meses e detalha o mês escolhido', async () => {
    mockFetch()
    renderizar()

    const grupo = await screen.findByRole('group', { name: 'Receitas e despesas por mês' })
    const colunas = within(grupo).getAllByRole('button')
    expect(colunas).toHaveLength(6)
    // Começa no mês da tela, o último da série.
    expect(colunas[5]).toHaveAttribute('aria-pressed', 'true')
    const detalhe = screen.getByRole('status', { name: 'Mês escolhido' })
    expect(detalhe).toHaveTextContent('outubro de 2026')
    expect(detalhe).toHaveTextContent(texto('Receitas R$ 5.000,00'))
    expect(detalhe).toHaveTextContent(texto('Despesas R$ 1.200,00'))
    expect(detalhe).toHaveTextContent(texto('Resultado R$ 3.800,00'))

    fireEvent.click(within(grupo).getByRole('button', { name: /setembro de 2026/ }))

    expect(colunas[4]).toHaveAttribute('aria-pressed', 'true')
    expect(colunas[5]).toHaveAttribute('aria-pressed', 'false')
    expect(detalhe).toHaveTextContent('setembro de 2026')
    expect(detalhe).toHaveTextContent(texto('Resultado -R$ 300,00'))
  })

  it('tem a tabela com todos os valores dos seis meses', async () => {
    mockFetch()
    renderizar()

    const tabela = await screen.findByRole('table', { name: 'Receitas e despesas por mês' })
    const linhas = within(tabela).getAllByRole('row')
    // Cabeçalho mais seis meses.
    expect(linhas).toHaveLength(7)
    expect(within(linhas[2]).getByText('junho de 2026')).toBeInTheDocument()
    expect(within(linhas[2]).getByText(texto('R$ 5.000,00'))).toBeInTheDocument()
    expect(within(linhas[2]).getByText(texto('R$ 900,00'))).toBeInTheDocument()
    expect(within(linhas[2]).getByText(texto('R$ 4.100,00'))).toBeInTheDocument()
  })

  it('troca de mês pelas setas e avisa quando o mês não tem despesa', async () => {
    mockFetch()
    renderizar()
    await screen.findByRole('list', { name: 'Despesas por categoria' })

    fireEvent.click(screen.getByRole('button', { name: 'Próximo mês' }))

    expect(await screen.findByText('Nenhuma despesa neste mês.')).toBeVisible()
    expect(chamadas()).toContain('/relatorios?mes=2026-11')
  })

  it('mostra a mensagem do servidor quando o relatório é recusado', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ erro: 'relatório fora do limite' }, 400)))
    renderizar()

    expect(await screen.findByRole('alert')).toHaveTextContent('relatório fora do limite')
  })

  it('não desenha resposta fora do contrato', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ ...outubro, totalDespesasCentavos: 1.5 }, 200)))
    renderizar()

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar o relatório.')
    expect(screen.queryByRole('list', { name: 'Despesas por categoria' })).toBeNull()
  })

  it('avisa quem chamou quando a sessão expira', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ erro: 'sessão expirada' }, 401)))
    const { aoSessaoExpirar } = renderizar()

    await vi.waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })

  it('leva ao Orçamento e às Metas pelos atalhos de planejamento', async () => {
    mockFetch()
    renderizar()

    const atalhos = screen.getByRole('navigation', { name: 'Planejamento' })
    expect(within(atalhos).getByRole('link', { name: 'Relatórios' })).toHaveAttribute('aria-current', 'page')
    expect(within(atalhos).getByRole('link', { name: 'Orçamento' })).toHaveAttribute('href', '#/orcamento')
    expect(within(atalhos).getByRole('link', { name: 'Metas' })).toHaveAttribute('href', '#/metas')
    await screen.findByRole('list', { name: 'Despesas por categoria' })
  })

  it('mostra o patrimônio no tempo do mesmo mês da tela', async () => {
    mockFetch()
    renderizar()

    expect(await screen.findByRole('region', { name: 'Patrimônio' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Mês anterior' }))
    await screen.findByText('Nenhuma despesa neste mês.')
    expect(chamadas()).toEqual(expect.arrayContaining(['/patrimonio/mensal?mes=2026-10', '/patrimonio/mensal?mes=2026-09']))
  })
})
