import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import Cripto from './Cripto'
import type { Conta } from './api'

function conta(id: string, nome: string): Conta {
  return {
    id,
    nome,
    tipo: 'cripto',
    moeda: 'BRL',
    saldoInicialCentavos: 0,
    saldoCentavos: 0,
    arquivada: false,
    criadaEm: '2026-10-19T12:00:00Z',
    cartao: null,
    categoriasPermitidas: null,
    percentualCdi: null,
    emInvestimentos: false,
  }
}

const exchange = conta('c1', 'Exchange')

const bitcoin = {
  id: 'a1',
  rede: 'bitcoin',
  contrato: null,
  simbolo: 'BTC',
  nome: 'Bitcoin',
  decimais: 8,
  confiavel: true,
}

const usdc = {
  id: 'a2',
  rede: 'ethereum',
  contrato: '0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48',
  simbolo: 'USDC',
  nome: null,
  decimais: 6,
  confiavel: true,
}

const compra = {
  id: 'm1',
  contaId: 'c1',
  ativoId: 'a1',
  tipo: 'compra',
  quantidade: '0.123456789012345678',
  data: '2026-10-01',
  custoUsdCentavos: 800_000,
  custoCentavos: 4_000_000,
}

const posicoes = {
  data: '2026-10-22',
  dolar: { valor: '5', data: '2026-10-21' },
  posicoes: [
    {
      contaId: 'c1',
      ativoId: 'a1',
      quantidade: '0.123456789012345678',
      custoUsdCentavos: 800_000,
      custoCentavos: 4_000_000,
      // 22:30 do dia 21 em São Paulo, já dia 22 em UTC.
      cotacao: { precoUsd: '70000.1', momento: '2026-10-22T01:30:00Z', fonte: 'coingecko' },
      valorUsdCentavos: 864_000,
      valorCentavos: 4_320_000,
      custoMedioUsd: '64800.00052488',
      custoDaPosicaoUsdCentavos: 800_000,
      custoDaPosicaoCentavos: 4_000_000,
      ganhoUsdCentavos: 64_000,
      ganhoCentavos: 320_000,
      realizadoUsdCentavos: 0,
      realizadoCentavos: 0,
    },
    {
      contaId: 'c1',
      ativoId: 'a2',
      quantidade: '10',
      custoUsdCentavos: 1_000,
      custoCentavos: 5_000,
      cotacao: null,
      valorUsdCentavos: null,
      valorCentavos: null,
      custoMedioUsd: '1',
      custoDaPosicaoUsdCentavos: 1_000,
      custoDaPosicaoCentavos: 5_000,
      ganhoUsdCentavos: null,
      ganhoCentavos: null,
      realizadoUsdCentavos: 0,
      realizadoCentavos: 0,
    },
  ],
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

type Rotas = Record<string, (corpo: unknown) => Promise<Response>>

const responde = (corpo: unknown, status = 200) => () => Promise.resolve(jsonResponse(corpo, status))

// Roteia por "MÉTODO caminho"; o que não estiver em rotas responde com a carteira padrão.
function mockFetch(rotas: Rotas = {}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const corpo: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      const rota = rotas[chave]
      if (rota) return rota(corpo)
      if (chave === 'GET /ativos-cripto') return Promise.resolve(jsonResponse([bitcoin, usdc], 200))
      if (chave === 'GET /carteiras') return Promise.resolve(jsonResponse([], 200))
      if (chave === 'GET /movimentos-cripto') return Promise.resolve(jsonResponse([compra], 200))
      if (chave.startsWith('GET /posicoes-cripto?')) return Promise.resolve(jsonResponse(posicoes, 200))
      return Promise.reject(new Error(`rota sem mock: ${chave}`))
    }),
  )
}

function chamadas(chave: string): unknown[] {
  return vi
    .mocked(fetch)
    .mock.calls.filter(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}` === chave)
    .map(([, init]) => (typeof init?.body === 'string' ? JSON.parse(init.body) : undefined))
}

function urlsPedidas(): string[] {
  return vi.mocked(fetch).mock.calls.map(([url]) => String(url))
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(/[ \u00a0]/g, '[\\s\\u00a0]'))

function renderizar(contas: Conta[] = [exchange]) {
  const aoSessaoExpirar = vi.fn()
  const aoMudar = vi.fn()
  render(<Cripto contas={contas} data="2026-10-22" aoMudar={aoMudar} aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar, aoMudar }
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-22T12:00:00-03:00'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Cripto', () => {
  it('avisa que não pede seed nem chave privada', async () => {
    mockFetch()
    renderizar()
    expect(screen.getByText(/nunca pede frase de recuperação \(seed\) nem chave privada/)).toBeVisible()
    await screen.findByRole('list', { name: 'Posições' })
    expect(urlsPedidas()).toContain('/posicoes-cripto?data=2026-10-22')
  })

  it('diz quando a cotação foi digitada', async () => {
    const digitada = {
      ...posicoes,
      posicoes: [{ ...posicoes.posicoes[0], cotacao: { precoUsd: '70000.1', momento: '2026-10-21T13:05:00Z', fonte: 'manual' } }],
    }
    mockFetch({ 'GET /posicoes-cripto?data=2026-10-22': responde(digitada) })
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Posições' })
    expect(within(lista).getByText(texto('Cotação US$ 70.000,1 em 21/10 às 10:05 · digitada'))).toBeVisible()
  })

  it('lista as posições com a quantidade inteira em texto, a cotação e o valor', async () => {
    mockFetch()
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Posições' })
    const [btc, semCotacao] = within(lista).getAllByRole('listitem')
    expect(within(btc).getByText('0,123456789012345678 BTC')).toBeVisible()
    expect(within(btc).getByText('BTC · Bitcoin · Exchange')).toBeVisible()
    // A hora é a de São Paulo, e a fonte diz se veio do job ou foi digitada (ADR-040).
    expect(within(btc).getByText(texto('Cotação US$ 70.000,1 em 21/10 às 22:30 · CoinGecko'))).toBeVisible()
    expect(within(btc).getByText(texto('Vale US$ 8.640,00'))).toBeVisible()
    expect(within(btc).getByText(texto('Compras menos vendas US$ 8.000,00 (R$ 40.000,00)'))).toBeVisible()
    expect(screen.getByText(texto('Dólar (PTAX) R$ 5 em 21/10/2026.'))).toBeVisible()
    expect(within(btc).getByText(texto('R$ 43.200,00'))).toBeVisible()
    expect(within(semCotacao).getByText('Sem cotação')).toBeVisible()
    expect(within(semCotacao).getByText('—')).toBeVisible()
  })

  it('separa o custo médio, o ganho em aberto e o das vendas', async () => {
    mockFetch()
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Posições' })
    const [btc, usdc] = within(lista).getAllByRole('listitem')
    expect(within(btc).getByText(texto('Custo médio US$ 64.800,00052488 por BTC'))).toBeVisible()
    expect(within(btc).getByText(texto('Ganho em aberto US$ 640,00 (R$ 3.200,00)'))).toBeVisible()
    // Sem venda não há o que realizar, e sem cotação não há ganho para mostrar.
    expect(within(btc).queryByText(/nas vendas/)).toBeNull()
    expect(within(usdc).queryByText(/em aberto/)).toBeNull()
  })

  it('mostra perda em aberto e o ganho realizado nas vendas', async () => {
    const comVenda = {
      ...posicoes,
      posicoes: [
        {
          ...posicoes.posicoes[0],
          ganhoUsdCentavos: -10_000,
          ganhoCentavos: -50_000,
          realizadoUsdCentavos: 5_000,
          realizadoCentavos: 25_000,
        },
      ],
    }
    mockFetch({ 'GET /posicoes-cripto?data=2026-10-22': responde(comVenda) })
    renderizar()

    const lista = await screen.findByRole('list', { name: 'Posições' })
    expect(within(lista).getByText(texto('Perda em aberto US$ 100,00 (R$ 500,00)'))).toBeVisible()
    expect(within(lista).getByText(texto('Ganho nas vendas US$ 50,00 (R$ 250,00)'))).toBeVisible()
  })

  it('sem conta cripto, explica onde criar e não oferece movimento', async () => {
    mockFetch()
    renderizar([])
    expect(await screen.findByText('Nenhuma conta cripto.')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Novo movimento' })).toBeNull()
  })

  it('registra uma compra com a quantidade em texto e o custo em centavos', async () => {
    mockFetch({ 'POST /movimentos-cripto': responde(compra, 201) })
    const { aoMudar } = renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Novo movimento' }))
    const formulario = screen.getByRole('form', { name: 'Novo movimento' })
    fireEvent.change(within(formulario).getByLabelText('Quantidade'), { target: { value: '0,123456789012345678' } })
    fireEvent.change(within(formulario).getByLabelText('Quanto pagou, em dólares'), { target: { value: '8.000,00' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Registrar compra' }))

    await waitFor(() => expect(chamadas('POST /movimentos-cripto')).toHaveLength(1))
    expect(chamadas('POST /movimentos-cripto')[0]).toEqual({
      contaId: 'c1',
      ativoId: 'a1',
      tipo: 'compra',
      quantidade: '0.123456789012345678',
      data: '2026-10-22',
      custoUsdCentavos: 800_000,
    })
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Novo movimento' })).toBeNull())
    expect(chamadas('GET /movimentos-cripto').length).toBeGreaterThan(1)
    // O patrimônio fica na tela de Investimentos; ela é avisada para buscar de novo.
    expect(aoMudar).toHaveBeenCalled()
  })

  it('recusa quantidade fora do formato sem chamar a API', async () => {
    mockFetch()
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Novo movimento' }))
    const formulario = screen.getByRole('form', { name: 'Novo movimento' })
    fireEvent.change(within(formulario).getByLabelText('Quantidade'), { target: { value: '1.234,5' } })
    fireEvent.change(within(formulario).getByLabelText('Quanto pagou, em dólares'), { target: { value: '10,00' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Registrar compra' }))

    expect(await within(formulario).findByRole('alert')).toHaveTextContent('Quantidade inválida')
    expect(chamadas('POST /movimentos-cripto')).toHaveLength(0)
  })

  it('mostra a recusa do servidor na venda maior que a posição', async () => {
    mockFetch({ 'POST /movimentos-cripto': responde({ erro: 'venda maior que a posição' }, 400) })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Novo movimento' }))
    const formulario = screen.getByRole('form', { name: 'Novo movimento' })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Venda' }))
    fireEvent.change(within(formulario).getByLabelText('Quantidade'), { target: { value: '5' } })
    fireEvent.change(within(formulario).getByLabelText('Quanto recebeu, em dólares'), { target: { value: '10,00' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Registrar venda' }))

    expect(await within(formulario).findByRole('alert')).toHaveTextContent('venda maior que a posição')
    expect(chamadas('POST /movimentos-cripto')[0]).toMatchObject({ tipo: 'venda' })
  })

  it('cadastra um ativo nativo sem contrato e um token com contrato', async () => {
    mockFetch({ 'POST /ativos-cripto': responde(bitcoin, 201) })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Novo ativo' }))
    let formulario = screen.getByRole('form', { name: 'Novo ativo' })
    expect(within(formulario).queryByLabelText('Contrato do token (opcional)')).toBeNull()
    fireEvent.change(within(formulario).getByLabelText('Símbolo'), { target: { value: 'BTC' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Cadastrar ativo' }))
    await waitFor(() => expect(chamadas('POST /ativos-cripto')).toHaveLength(1))
    expect(chamadas('POST /ativos-cripto')[0]).toEqual({
      rede: 'bitcoin',
      contrato: null,
      simbolo: 'BTC',
      nome: null,
      decimais: 8,
    })

    await waitFor(() => expect(screen.queryByRole('form', { name: 'Novo ativo' })).toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Novo ativo' }))
    formulario = screen.getByRole('form', { name: 'Novo ativo' })
    fireEvent.change(within(formulario).getByLabelText('Rede'), { target: { value: 'ethereum' } })
    expect(within(formulario).getByLabelText('Casas decimais')).toHaveValue(18)
    fireEvent.change(within(formulario).getByLabelText('Contrato do token (opcional)'), {
      target: { value: usdc.contrato },
    })
    fireEvent.change(within(formulario).getByLabelText('Símbolo'), { target: { value: 'USDC' } })
    fireEvent.change(within(formulario).getByLabelText('Casas decimais'), { target: { value: '6' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Cadastrar ativo' }))
    await waitFor(() => expect(chamadas('POST /ativos-cripto')).toHaveLength(2))
    expect(chamadas('POST /ativos-cripto')[1]).toMatchObject({ rede: 'ethereum', contrato: usdc.contrato, decimais: 6 })
  })

  it('mostra o 409 do ativo repetido', async () => {
    mockFetch({ 'POST /ativos-cripto': responde({ erro: 'ativo já cadastrado' }, 409) })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Novo ativo' }))
    const formulario = screen.getByRole('form', { name: 'Novo ativo' })
    fireEvent.change(within(formulario).getByLabelText('Símbolo'), { target: { value: 'BTC' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Cadastrar ativo' }))
    expect(await within(formulario).findByRole('alert')).toHaveTextContent('ativo já cadastrado')
  })

  it('salva a cotação manual com o preço em texto', async () => {
    mockFetch({ 'POST /ativos-cripto/a1/cotacoes': responde({}, 201) })
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Posições' })
    fireEvent.click(within(lista).getByRole('button', { name: 'Informar cotação de BTC' }))
    const formulario = screen.getByRole('form', { name: 'Cotação de BTC' })
    fireEvent.change(within(formulario).getByLabelText('Preço de 1 BTC em dólares'), { target: { value: '70.000,50' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Salvar cotação' }))
    await waitFor(() => expect(chamadas('POST /ativos-cripto/a1/cotacoes')).toEqual([{ precoUsd: '70000.50' }]))
  })

  it('apaga um movimento em dois cliques e mostra a recusa da posição negativa', async () => {
    mockFetch({
      'DELETE /movimentos-cripto/m1': responde({ erro: 'apagar deixaria a posição negativa' }, 400),
    })
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Movimentos' })
    fireEvent.click(within(lista).getByRole('button', { name: /^Apagar Compra de 0,123456789012345678 BTC/ }))
    expect(chamadas('DELETE /movimentos-cripto/m1')).toHaveLength(0)
    fireEvent.click(within(lista).getByRole('button', { name: 'Confirmar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('apagar deixaria a posição negativa')
    expect(chamadas('DELETE /movimentos-cripto/m1')).toHaveLength(1)
  })

  it('avisa quem perdeu a sessão', async () => {
    mockFetch({ 'GET /ativos-cripto': responde({ erro: 'sem sessão' }, 401) })
    const { aoSessaoExpirar } = renderizar()
    await waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })

})
