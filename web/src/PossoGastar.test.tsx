import { render, screen, within } from '@testing-library/react'
import PossoGastar from './PossoGastar'

const base = {
  hoje: '2026-03-15',
  ate: '2026-04-04',
  proximoSalario: { data: '2026-04-05', valorCentavos: 500_000 },
  livreCentavos: 60_000,
  menorSaldoEm: '2026-03-25',
  dias: 21,
  porDiaCentavos: 2_857,
  vencidasCentavos: 0,
  beneficios: [],
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

function mockFetch(corpo: unknown, status = 200): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(jsonResponse(corpo, status))),
  )
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function renderizar(versao = 0) {
  const aoSessaoExpirar = vi.fn()
  const resultado = render(<PossoGastar versao={versao} aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar, ...resultado }
}

describe('PossoGastar', () => {
  it('mostra quanto dá para gastar até a véspera do salário e quanto por dia', async () => {
    mockFetch(base)
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText(texto('R$ 600,00'), { selector: 'strong' })).toBeVisible()
    expect(within(cartao).getByText('até 04/04, véspera do salário')).toBeVisible()
    expect(within(cartao).getByText(texto('R$ 28,57 por dia nos próximos 21 dias'))).toBeVisible()
    expect(within(cartao).getByText('O saldo chega ao menor valor em 25/03.')).toBeVisible()
    expect(vi.mocked(fetch).mock.calls.map(([url]) => String(url))).toContain('/posso-gastar')
  })

  it('sem salário previsto, conta até o fim do mês e diz isso', async () => {
    mockFetch({ ...base, proximoSalario: null, ate: '2026-03-31', dias: 17, menorSaldoEm: '2026-03-15' })
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText('até 31/03, fim do mês')).toBeVisible()
    expect(within(cartao).getByText('Nenhum salário previsto. Cadastre a recorrência na categoria Salário.')).toBeVisible()
    // O menor saldo hoje não precisa de aviso.
    expect(within(cartao).queryByText(/menor valor/)).toBeNull()
  })

  it('o último dia antes do salário fala de hoje, não de dias', async () => {
    mockFetch({ ...base, ate: '2026-03-15', dias: 1, porDiaCentavos: 60_000, menorSaldoEm: '2026-03-15' })
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText('até hoje, véspera do salário')).toBeVisible()
    expect(within(cartao).queryByText(/por dia/)).toBeNull()
  })

  it('quando falta dinheiro, diz quanto falta em vez de quanto sobra', async () => {
    mockFetch({ ...base, livreCentavos: -25_000, porDiaCentavos: null, menorSaldoEm: '2026-03-28' })
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText(texto('Faltam R$ 250,00'), { selector: 'strong' })).toBeVisible()
    expect(within(cartao).getByText('para cobrir o que está previsto até 04/04. O saldo fica negativo em 28/03.')).toBeVisible()
    expect(within(cartao).queryByText(/por dia/)).toBeNull()
  })

  it('avisa quando o valor conta com previstas vencidas', async () => {
    mockFetch({ ...base, vencidasCentavos: -15_000 })
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Posso gastar' })
    expect(await within(cartao).findByText(texto('Já desconta R$ 150,00 de despesas vencidas que ainda não foram pagas.'))).toBeVisible()
  })

  it('mostra cada benefício à parte, fora do valor livre', async () => {
    mockFetch({ ...base, beneficios: [{ contaId: 'vr', nome: 'VR', livreCentavos: 55_000 }] })
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Benefícios' })
    const [vr] = within(lista).getAllByRole('listitem')
    expect(vr).toHaveTextContent('VR')
    expect(vr).toHaveTextContent(texto('R$ 550,00'))
    expect(within(lista.parentElement as HTMLElement).getByText('Só nas categorias de cada benefício.')).toBeVisible()
  })

  it('busca de novo quando os lançamentos mudam', async () => {
    mockFetch(base)
    const { rerender } = renderizar()
    await screen.findByText(texto('R$ 600,00'), { selector: 'strong' })

    rerender(<PossoGastar versao={1} aoSessaoExpirar={vi.fn()} />)

    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(2))
  })

  it('erro avisa sem quebrar o Início, e a sessão expirada volta ao login', async () => {
    mockFetch({ erro: 'projeção fora do limite' }, 400)
    renderizar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível calcular quanto dá para gastar.')

    mockFetch({ erro: 'sessão expirada' }, 401)
    const { aoSessaoExpirar } = renderizar()
    await vi.waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })
})
