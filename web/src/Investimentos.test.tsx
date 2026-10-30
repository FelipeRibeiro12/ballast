import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import Investimentos from './Investimentos'

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

const patrimonio = {
  data: '2026-10-22',
  contasCentavos: 500_000,
  criptoCentavos: 4_320_000,
  criptoAportadoCentavos: 4_000_000,
  criptoValorizacaoCentavos: 320_000,
  rendaFixaCentavos: 1_050_000,
  cofrinhosCentavos: 0,
  cofrinhos: [],
  totalCentavos: 5_870_000,
  semCotacao: [{ contaId: 'c1', ativoId: 'a2', quantidade: '10' }],
}

const vazio = { data: '2026-10-22', indices: { cdiAte: '2026-10-21', ipcaAte: '2026-09-01' }, titulos: [] }

function mockFetch(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string) => {
      if (url.startsWith('/patrimonio?')) return Promise.resolve(jsonResponse({ ...patrimonio, data: url.slice(-10) }, 200))
      if (url.startsWith('/titulos-renda-fixa?')) return Promise.resolve(jsonResponse(vazio, 200))
      if (url === '/ativos-cripto' || url === '/movimentos-cripto') return Promise.resolve(jsonResponse([], 200))
      if (url.startsWith('/posicoes-cripto?')) return Promise.resolve(jsonResponse({ data: '2026-10-22', posicoes: [] }, 200))
      return Promise.reject(new Error(`rota sem mock: ${url}`))
    }),
  )
}

function urlsPedidas(): string[] {
  return vi.mocked(fetch).mock.calls.map(([url]) => String(url))
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-22T12:00:00-03:00'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Investimentos', () => {
  it('mostra só renda fixa e cripto no total, sem o saldo das contas, e o aviso das sem cotação', async () => {
    mockFetch()
    render(<Investimentos contasDeInvestimento={[]} contasCripto={[]} cofrinhosAtivos={[]} aoMudarCofrinho={vi.fn()} aoSessaoExpirar={vi.fn()} />)

    const resumo = await screen.findByRole('region', { name: 'Total investido' })
    expect(within(resumo).getByText(texto('R$ 53.700,00'))).toBeVisible()
    expect(within(resumo).queryByText('Contas')).toBeNull()
    expect(within(resumo).queryByText(texto('R$ 5.000,00'))).toBeNull()
    expect(within(resumo).getByText(texto('R$ 10.500,00'))).toBeVisible()
    expect(within(resumo).getByText(texto('R$ 43.200,00'))).toBeVisible()
    expect(within(resumo).getByRole('status')).toHaveTextContent('1 posição sem cotação ficou fora do total.')
    expect(urlsPedidas()).toContain('/patrimonio?data=2026-10-22')
  })

  it('separa na cripto o que foi aporte do que foi valorização', async () => {
    mockFetch()
    render(<Investimentos contasDeInvestimento={[]} contasCripto={[]} cofrinhosAtivos={[]} aoMudarCofrinho={vi.fn()} aoSessaoExpirar={vi.fn()} />)

    const resumo = await screen.findByRole('region', { name: 'Total investido' })
    expect(
      within(resumo).getByText(texto('Na cripto, R$ 40.000,00 de aporte e R$ 3.200,00 de valorização.')),
    ).toBeVisible()
  })

  it('abre em renda fixa e troca para cripto', async () => {
    mockFetch()
    render(<Investimentos contasDeInvestimento={[]} contasCripto={[]} cofrinhosAtivos={[]} aoMudarCofrinho={vi.fn()} aoSessaoExpirar={vi.fn()} />)

    expect(await screen.findByRole('region', { name: 'Renda fixa' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Cripto' }))
    expect(await screen.findByRole('region', { name: 'Cripto' })).toBeVisible()
    expect(screen.queryByRole('region', { name: 'Renda fixa' })).toBeNull()
  })

  it('a data vale para o patrimônio e para a aba aberta', async () => {
    mockFetch()
    render(<Investimentos contasDeInvestimento={[]} contasCripto={[]} cofrinhosAtivos={[]} aoMudarCofrinho={vi.fn()} aoSessaoExpirar={vi.fn()} />)
    await screen.findByRole('region', { name: 'Total investido' })

    fireEvent.change(screen.getByLabelText('Posição em'), { target: { value: '2026-03-10' } })

    await waitFor(() => expect(urlsPedidas()).toContain('/patrimonio?data=2026-03-10'))
    expect(urlsPedidas()).toContain('/titulos-renda-fixa?data=2026-03-10')
  })
})
