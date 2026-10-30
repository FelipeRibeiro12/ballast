import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { Conta } from './api'
import Investimentos from './Investimentos'

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

function cofrinho(id: string, nome: string, emInvestimentos: boolean): Conta {
  return {
    id,
    nome,
    tipo: 'cofrinho',
    moeda: 'BRL',
    saldoInicialCentavos: 0,
    saldoCentavos: 0,
    arquivada: false,
    criadaEm: '2026-01-01T00:00:00Z',
    cartao: null,
    categoriasPermitidas: null,
    percentualCdi: '100.00',
    emInvestimentos,
  }
}

const patrimonio = {
  data: '2026-10-22',
  contasCentavos: 700_000,
  criptoCentavos: 4_320_000,
  criptoUsdCentavos: null,
  criptoAportadoCentavos: null,
  criptoValorizacaoCentavos: null,
  rendaFixaCentavos: 1_050_000,
  cofrinhosCentavos: 200_000,
  cofrinhos: [{ contaId: 'k1', nome: 'Cofrinho MP', saldoCentavos: 200_000 }],
  totalCentavos: 6_070_000,
  semCotacao: [],
}

const vazio = { data: '2026-10-22', indices: { cdiAte: '2026-10-21', ipcaAte: '2026-09-01' }, titulos: [] }

function mockFetch(put: (url: string, corpo: unknown) => Response | Promise<Response>): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      if (url.startsWith('/patrimonio?')) return Promise.resolve(jsonResponse(patrimonio, 200))
      if (url.startsWith('/titulos-renda-fixa?')) return Promise.resolve(jsonResponse(vazio, 200))
      if (url.endsWith('/investimentos') && init?.method === 'PUT') {
        return Promise.resolve(put(url, JSON.parse(String(init.body))))
      }
      return Promise.reject(new Error(`rota sem mock: ${url}`))
    }),
  )
}

const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function montar(cofrinhos: Conta[], aoMudarCofrinho = vi.fn()): void {
  render(
    <Investimentos
      contasDeInvestimento={[]}
      contasCripto={[]}
      cofrinhosAtivos={cofrinhos}
      aoMudarCofrinho={aoMudarCofrinho}
      aoSessaoExpirar={vi.fn()}
    />,
  )
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-22T12:00:00-03:00'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('cofrinhos na renda fixa', () => {
  it('soma os cofrinhos marcados à renda fixa e ao total, e lista cada um com o saldo', async () => {
    mockFetch(() => jsonResponse({}, 200))
    montar([cofrinho('k1', 'Cofrinho MP', true)])

    const resumo = await screen.findByRole('region', { name: 'Total investido' })
    // Renda fixa 10.500 + cofrinho 2.000 + cripto 43.200.
    expect(within(resumo).getByText(texto('R$ 55.700,00'))).toBeVisible()
    expect(within(resumo).getByText(texto('R$ 12.500,00'))).toBeVisible()
    const lista = await screen.findByRole('list', { name: 'Cofrinhos na renda fixa' })
    expect(within(lista).getByText('Cofrinho MP')).toBeVisible()
    expect(within(lista).getByText(texto('R$ 2.000,00'))).toBeVisible()
  })

  it('a engrenagem abre com foco no primeiro interruptor e Esc fecha', async () => {
    mockFetch(() => jsonResponse({}, 200))
    montar([cofrinho('k1', 'Cofrinho MP', false), cofrinho('k2', 'Cofrinho Nubank', false)])

    const engrenagem = await screen.findByRole('button', { name: 'Escolher cofrinhos da renda fixa' })
    fireEvent.click(engrenagem)

    const dialogo = await screen.findByRole('dialog')
    expect(within(dialogo).getByRole('checkbox', { name: 'Cofrinho MP' })).toHaveFocus()
    fireEvent.keyDown(dialogo, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('cada conta tem o próprio interruptor, sem marcar todas, e salva só a que mudou', async () => {
    const corpos: Array<{ url: string; corpo: unknown }> = []
    mockFetch((url, corpo) => {
      corpos.push({ url, corpo })
      return jsonResponse(cofrinho('k2', 'Cofrinho Nubank', true), 200)
    })
    const aoMudarCofrinho = vi.fn()
    montar([cofrinho('k1', 'Cofrinho MP', false), cofrinho('k2', 'Cofrinho Nubank', false)], aoMudarCofrinho)

    fireEvent.click(await screen.findByRole('button', { name: 'Escolher cofrinhos da renda fixa' }))
    const dialogo = await screen.findByRole('dialog')
    expect(within(dialogo).getAllByRole('checkbox')).toHaveLength(2)
    expect(within(dialogo).queryByRole('button', { name: /todas|todos/i })).toBeNull()
    expect(within(dialogo).getByRole('checkbox', { name: 'Cofrinho MP' })).not.toBeChecked()

    fireEvent.click(within(dialogo).getByRole('checkbox', { name: 'Cofrinho Nubank' }))

    await waitFor(() => expect(aoMudarCofrinho).toHaveBeenCalledTimes(1))
    expect(corpos).toEqual([{ url: '/contas/k2/investimentos', corpo: { emInvestimentos: true } }])
    expect(within(dialogo).getByRole('checkbox', { name: 'Cofrinho Nubank' })).toBeChecked()
    expect(within(dialogo).getByRole('checkbox', { name: 'Cofrinho MP' })).not.toBeChecked()
  })

  it('mostra a mensagem quando a rede falha e não muda o interruptor', async () => {
    mockFetch(() => {
      throw new TypeError('Failed to fetch')
    })
    montar([cofrinho('k1', 'Cofrinho MP', false)])

    fireEvent.click(await screen.findByRole('button', { name: 'Escolher cofrinhos da renda fixa' }))
    const dialogo = await screen.findByRole('dialog')
    fireEvent.click(within(dialogo).getByRole('checkbox', { name: 'Cofrinho MP' }))

    expect(await within(dialogo).findByRole('alert')).toHaveTextContent('Não foi possível salvar a escolha')
    expect(within(dialogo).getByRole('checkbox', { name: 'Cofrinho MP' })).not.toBeChecked()
  })

  it('sem cofrinho ativo, explica onde criar', async () => {
    mockFetch(() => jsonResponse({}, 200))
    montar([])

    fireEvent.click(await screen.findByRole('button', { name: 'Escolher cofrinhos da renda fixa' }))

    expect(await screen.findByText(/Nenhum cofrinho ativo/)).toBeVisible()
  })
})
