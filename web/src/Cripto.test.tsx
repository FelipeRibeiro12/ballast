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
  custoCentavos: 4_000_000,
}

const patrimonio = {
  data: '2026-10-22',
  contasCentavos: 500_000,
  criptoCentavos: 4_320_000,
  totalCentavos: 4_820_000,
  semCotacao: [{ contaId: 'c1', ativoId: 'a2', quantidade: '10' }],
}

const posicoes = {
  data: '2026-10-22',
  posicoes: [
    {
      contaId: 'c1',
      ativoId: 'a1',
      quantidade: '0.123456789012345678',
      custoCentavos: 4_000_000,
      // 22:30 do dia 21 em São Paulo, já dia 22 em UTC.
      cotacao: { preco: '350000.5', momento: '2026-10-22T01:30:00Z' },
      valorCentavos: 4_320_000,
    },
    {
      contaId: 'c1',
      ativoId: 'a2',
      quantidade: '10',
      custoCentavos: 5_000,
      cotacao: null,
      valorCentavos: null,
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
      if (chave === 'GET /movimentos-cripto') return Promise.resolve(jsonResponse([compra], 200))
      if (chave.startsWith('GET /patrimonio?')) return Promise.resolve(jsonResponse(patrimonio, 200))
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
  render(<Cripto contas={contas} aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar }
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
    await screen.findByRole('region', { name: 'Patrimônio' })
  })

  it('mostra o patrimônio de hoje com contas, cripto e o aviso das posições sem cotação', async () => {
    mockFetch()
    renderizar()
    const resumo = await screen.findByRole('region', { name: 'Patrimônio' })
    expect(within(resumo).getByText(texto('R$ 48.200,00'))).toBeVisible()
    expect(within(resumo).getByText(texto('R$ 5.000,00'))).toBeVisible()
    expect(within(resumo).getByText(texto('R$ 43.200,00'))).toBeVisible()
    expect(within(resumo).getByRole('status')).toHaveTextContent('1 posição sem cotação ficou fora do total.')
    expect(urlsPedidas()).toContain('/patrimonio?data=2026-10-22')
    expect(urlsPedidas()).toContain('/posicoes-cripto?data=2026-10-22')
  })

  it('lista as posições com a quantidade inteira em texto, a cotação e o valor', async () => {
    mockFetch()
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Posições' })
    const [btc, semCotacao] = within(lista).getAllByRole('listitem')
    expect(within(btc).getByText('0,123456789012345678 BTC')).toBeVisible()
    expect(within(btc).getByText('BTC · Bitcoin · Exchange')).toBeVisible()
    expect(within(btc).getByText(texto('Cotação R$ 350.000,5 em 21/10'))).toBeVisible()
    expect(within(btc).getByText(texto('R$ 43.200,00'))).toBeVisible()
    expect(within(semCotacao).getByText('Sem cotação')).toBeVisible()
    expect(within(semCotacao).getByText('—')).toBeVisible()
  })

  it('busca de novo ao trocar a data', async () => {
    mockFetch()
    renderizar()
    await screen.findByRole('list', { name: 'Posições' })
    fireEvent.change(screen.getByLabelText('Posição em'), { target: { value: '2026-03-10' } })
    await waitFor(() => expect(urlsPedidas()).toContain('/posicoes-cripto?data=2026-03-10'))
    expect(urlsPedidas()).toContain('/patrimonio?data=2026-03-10')
  })

  it('sem conta cripto, explica onde criar e não oferece movimento', async () => {
    mockFetch()
    renderizar([])
    expect(await screen.findByText('Nenhuma conta cripto.')).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Novo movimento' })).toBeNull()
  })

  it('registra uma compra com a quantidade em texto e o custo em centavos', async () => {
    mockFetch({ 'POST /movimentos-cripto': responde(compra, 201) })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Novo movimento' }))
    const formulario = screen.getByRole('form', { name: 'Novo movimento' })
    fireEvent.change(within(formulario).getByLabelText('Quantidade'), { target: { value: '0,123456789012345678' } })
    fireEvent.change(within(formulario).getByLabelText('Quanto pagou, em reais'), { target: { value: '40.000,00' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Registrar compra' }))

    await waitFor(() => expect(chamadas('POST /movimentos-cripto')).toHaveLength(1))
    expect(chamadas('POST /movimentos-cripto')[0]).toEqual({
      contaId: 'c1',
      ativoId: 'a1',
      tipo: 'compra',
      quantidade: '0.123456789012345678',
      data: '2026-10-22',
      custoCentavos: 4_000_000,
    })
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Novo movimento' })).toBeNull())
    expect(chamadas('GET /movimentos-cripto').length).toBeGreaterThan(1)
  })

  it('recusa quantidade fora do formato sem chamar a API', async () => {
    mockFetch()
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Novo movimento' }))
    const formulario = screen.getByRole('form', { name: 'Novo movimento' })
    fireEvent.change(within(formulario).getByLabelText('Quantidade'), { target: { value: '1.234,5' } })
    fireEvent.change(within(formulario).getByLabelText('Quanto pagou, em reais'), { target: { value: '10,00' } })
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
    fireEvent.change(within(formulario).getByLabelText('Quanto recebeu, em reais'), { target: { value: '10,00' } })
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
    fireEvent.change(within(formulario).getByLabelText('Preço de 1 BTC em reais'), { target: { value: '350.000,50' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Salvar cotação' }))
    await waitFor(() => expect(chamadas('POST /ativos-cripto/a1/cotacoes')).toEqual([{ preco: '350000.50' }]))
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

  it('mostra erro quando o patrimônio fica fora do limite', async () => {
    mockFetch({ 'GET /patrimonio?data=2026-10-22': responde({ erro: 'patrimônio fora do limite' }, 400) })
    renderizar()
    expect(await screen.findByText('patrimônio fora do limite')).toBeVisible()
  })
})
