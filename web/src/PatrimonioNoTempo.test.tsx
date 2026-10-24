import { fireEvent, render, screen, within } from '@testing-library/react'
import PatrimonioNoTempo from './PatrimonioNoTempo'

function mes(m: string, data: string, contas: number, cripto: number, rendaFixa: number, semCotacao = 0) {
  return {
    mes: m,
    data,
    contasCentavos: contas,
    criptoCentavos: cripto,
    rendaFixaCentavos: rendaFixa,
    totalCentavos: contas + cripto + rendaFixa,
    posicoesSemCotacao: semCotacao,
  }
}

const serie = {
  mes: '2026-10',
  meses: [
    mes('2025-11', '2025-11-30', 1_000_000, 0, 0),
    mes('2025-12', '2025-12-31', 1_100_000, 0, 0),
    mes('2026-01', '2026-01-31', 1_150_000, 0, 0),
    mes('2026-02', '2026-02-28', 1_200_000, 0, 0),
    mes('2026-03', '2026-03-31', 1_250_000, 0, 0, 1),
    mes('2026-04', '2026-04-30', 1_300_000, 50_000, 0),
    mes('2026-05', '2026-05-31', 1_300_000, 60_000, 0),
    mes('2026-06', '2026-06-30', 1_350_000, 60_000, 100_000),
    mes('2026-07', '2026-07-31', 1_400_000, 70_000, 101_000),
    mes('2026-08', '2026-08-31', 1_450_000, 80_000, 102_000),
    mes('2026-09', '2026-09-30', 1_500_000, 90_000, 103_000),
    mes('2026-10', '2026-10-22', 1_550_000, 100_000, 104_000),
  ],
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

function mockFetch(resposta: () => Promise<Response> = () => Promise.resolve(jsonResponse(serie, 200))): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => resposta()),
  )
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function renderizar(mesDaTela = { ano: 2026, mes: 10 }) {
  const aoSessaoExpirar = vi.fn()
  render(<PatrimonioNoTempo mes={mesDaTela} aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar }
}

describe('PatrimonioNoTempo', () => {
  it('mostra o patrimônio do último mês e quanto mudou desde o primeiro', async () => {
    mockFetch()
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Patrimônio' })
    expect(await within(cartao).findByText(texto('R$ 17.540,00'), { selector: 'strong' })).toBeVisible()
    expect(within(cartao).getByText(texto('+R$ 7.540,00 desde novembro de 2025'))).toBeVisible()
    expect(vi.mocked(fetch).mock.calls.map(([url]) => String(url))).toContain('/patrimonio/mensal?mes=2026-10')
  })

  it('detalha o mês escolhido em contas, cripto e renda fixa', async () => {
    mockFetch()
    renderizar()

    const grafico = await screen.findByRole('group', { name: 'Patrimônio por mês' })
    const pontos = within(grafico).getAllByRole('button')
    expect(pontos).toHaveLength(12)
    expect(pontos[11]).toHaveAttribute('aria-pressed', 'true')
    const detalhe = screen.getByRole('status', { name: 'Mês escolhido' })
    expect(detalhe).toHaveTextContent('outubro de 2026, até 22/10')
    expect(detalhe).toHaveTextContent(texto('Contas R$ 15.500,00'))
    expect(detalhe).toHaveTextContent(texto('Cripto R$ 1.000,00'))
    expect(detalhe).toHaveTextContent(texto('Renda fixa R$ 1.040,00'))

    fireEvent.click(within(grafico).getByRole('button', { name: /junho de 2026/ }))

    expect(pontos[7]).toHaveAttribute('aria-pressed', 'true')
    expect(detalhe).toHaveTextContent('junho de 2026')
    expect(detalhe).not.toHaveTextContent('até')
    expect(detalhe).toHaveTextContent(texto('Total R$ 15.100,00'))
  })

  it('o teclado escolhe o mês como o toque', async () => {
    mockFetch()
    renderizar()

    const grafico = await screen.findByRole('group', { name: 'Patrimônio por mês' })
    fireEvent.keyDown(within(grafico).getByRole('button', { name: /janeiro de 2026/ }), { key: 'Enter' })

    expect(screen.getByRole('status', { name: 'Mês escolhido' })).toHaveTextContent('janeiro de 2026')
  })

  it('avisa o mês com posição de cripto sem cotação, que ficou fora do total', async () => {
    mockFetch()
    renderizar()

    const cartao = await screen.findByRole('region', { name: 'Patrimônio' })
    expect(await within(cartao).findByText('Em 1 mês alguma posição de cripto ficou fora por falta de cotação.')).toBeVisible()
    fireEvent.click(within(cartao).getByRole('button', { name: /março de 2026/ }))
    expect(screen.getByRole('status', { name: 'Mês escolhido' })).toHaveTextContent(
      '1 posição sem cotação ficou fora do total',
    )
  })

  it('tem a tabela com cada mês e cada parte', async () => {
    mockFetch()
    renderizar()

    const tabela = await screen.findByRole('table', { name: 'Patrimônio por mês' })
    const linhas = within(tabela).getAllByRole('row')
    expect(linhas).toHaveLength(13)
    expect(within(linhas[12]).getByText('outubro de 2026')).toBeInTheDocument()
    expect(within(linhas[12]).getByText(texto('R$ 17.540,00'))).toBeInTheDocument()
  })

  it('período inteiro no futuro não tem o que desenhar', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ mes: '2028-01', meses: [] }, 200)))
    renderizar({ ano: 2028, mes: 1 })

    expect(await screen.findByText('Sem patrimônio para mostrar neste período.')).toBeVisible()
  })

  it('mostra a mensagem do servidor e avisa a sessão expirada', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ erro: 'patrimônio fora do limite' }, 400)))
    renderizar()
    expect(await screen.findByRole('alert')).toHaveTextContent('patrimônio fora do limite')
  })

  it('não desenha resposta fora do contrato', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ ...serie, meses: [{ ...serie.meses[0], totalCentavos: '1' }] }, 200)))
    renderizar()
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar o patrimônio.')
  })

  it('avisa quem chamou quando a sessão expira', async () => {
    mockFetch(() => Promise.resolve(jsonResponse({ erro: 'sessão expirada' }, 401)))
    const { aoSessaoExpirar } = renderizar()
    await vi.waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })
})
