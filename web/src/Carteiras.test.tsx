import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import Cripto from './Cripto'
import type { Conta } from './api'

const exchange: Conta = {
  id: 'c1',
  nome: 'Exchange',
  tipo: 'cripto',
  moeda: 'BRL',
  saldoInicialCentavos: 0,
  saldoCentavos: 0,
  arquivada: false,
  criadaEm: '2026-10-19T12:00:00Z',
  cartao: null,
  categoriasPermitidas: null,
  percentualCdi: null,
}

const ENDERECO = '0x52908400098527886e0f7030069857d2e4169ee7'

const eth = { id: 'a1', rede: 'ethereum', contrato: null, simbolo: 'ETH', nome: 'Ether', decimais: 18, confiavel: true }
const golpe = { id: 'a2', rede: 'ethereum', contrato: '0xabc0000000000000000000000000000000000001', simbolo: 'SCAM', nome: null, decimais: 6, confiavel: false }

const entradaSemCusto = {
  id: 'm1',
  contaId: 'c1',
  ativoId: 'a1',
  tipo: 'entrada',
  origem: 'sincronizada',
  quantidade: '1.5',
  data: '2026-10-22',
  custoUsdCentavos: null,
  custoCentavos: null,
}

const carteira = {
  id: 'w1',
  contaId: 'c1',
  rede: 'ethereum',
  endereco: ENDERECO,
  rotulo: 'Cofre',
  // 12:30 em São Paulo.
  sincronizadaEm: '2026-10-22T15:30:00Z',
  leituraFalhou: false,
  saldos: [
    { ativoId: 'a1', simbolo: 'ETH', contrato: null, quantidade: '1.5', confiavel: true, custoPendente: true },
    { ativoId: 'a2', simbolo: 'SCAM', contrato: golpe.contrato, quantidade: '1000000.000001', confiavel: false, custoPendente: false },
  ],
}

const posicoesVazias = { data: '2026-10-22', dolar: null, posicoes: [] }

function json(corpo: unknown, status = 200): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

type Rotas = Record<string, (corpo: unknown) => Response>

function mockFetch(rotas: Rotas = {}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const corpo: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      const rota = rotas[chave]
      if (rota) return Promise.resolve(rota(corpo))
      if (chave === 'GET /ativos-cripto') return Promise.resolve(json([eth, golpe]))
      if (chave === 'GET /movimentos-cripto') return Promise.resolve(json([entradaSemCusto]))
      if (chave === 'GET /carteiras') return Promise.resolve(json([carteira]))
      if (chave.startsWith('GET /posicoes-cripto?')) return Promise.resolve(json(posicoesVazias))
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

function renderizar(): { aoSessaoExpirar: () => void } {
  const aoSessaoExpirar = vi.fn()
  render(<Cripto contas={[exchange]} data="2026-10-22" aoMudar={vi.fn()} aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar }
}

async function abrirCadastro(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: 'Observar carteira' }))
  return screen.getByRole('form', { name: 'Nova carteira' })
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('Carteiras', () => {
  it('diz que só observa e nunca pede seed, chave nem assinatura', async () => {
    mockFetch()
    renderizar()
    const avisos = await screen.findAllByText(/nunca pede seed, chave privada nem assinatura de transação/)
    expect(avisos.length).toBeGreaterThan(0)
  })

  it('cadastra digitando o endereço, sem window.ethereum, e não põe o endereço em URL', async () => {
    expect('ethereum' in window).toBe(false)
    const criada = { ...carteira, sincronizadaEm: null, saldos: [] }
    mockFetch({ 'POST /carteiras': () => json(criada, 201) })
    renderizar()
    const formulario = await abrirCadastro()
    fireEvent.change(within(formulario).getByLabelText('Rede'), { target: { value: 'base' } })
    fireEvent.change(within(formulario).getByLabelText('Endereço'), { target: { value: `  ${ENDERECO} ` } })
    fireEvent.change(within(formulario).getByLabelText('Rótulo (opcional)'), { target: { value: 'Cofre' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Observar carteira' }))

    await waitFor(() => expect(chamadas('POST /carteiras')).toHaveLength(1))
    expect(chamadas('POST /carteiras')[0]).toEqual({ contaId: 'c1', rede: 'base', endereco: ENDERECO, rotulo: 'Cofre' })
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Nova carteira' })).toBeNull())

    for (const [url] of vi.mocked(fetch).mock.calls) {
      expect(String(url).toLowerCase()).not.toContain(ENDERECO)
    }
  })

  it('não cadastra endereço vazio e não chama a API', async () => {
    mockFetch()
    renderizar()
    const formulario = await abrirCadastro()
    fireEvent.change(within(formulario).getByLabelText('Endereço'), { target: { value: '   ' } })
    fireEvent.submit(formulario)
    expect(await within(formulario).findByRole('alert')).toHaveTextContent('Informe o endereço público')
    expect(chamadas('POST /carteiras')).toHaveLength(0)
  })

  it('mostra o erro do servidor para endereço inválido e para duplicada', async () => {
    mockFetch({ 'POST /carteiras': () => json({ erro: 'endereço inválido para a rede' }, 400) })
    renderizar()
    const formulario = await abrirCadastro()
    fireEvent.change(within(formulario).getByLabelText('Endereço'), { target: { value: '0x123' } })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Observar carteira' }))
    expect(await within(formulario).findByRole('alert')).toHaveTextContent('endereço inválido para a rede')

    mockFetch({ 'POST /carteiras': () => json({ erro: 'carteira já cadastrada' }, 409) })
    fireEvent.click(within(formulario).getByRole('button', { name: 'Observar carteira' }))
    await waitFor(() => expect(within(formulario).getByRole('alert')).toHaveTextContent('carteira já cadastrada'))
  })

  it('lista rótulo, rede, endereço abreviado e a última leitura em horário de São Paulo', async () => {
    mockFetch()
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Carteiras' })
    expect(within(lista).getByText('Cofre')).toBeVisible()
    expect(within(lista).getByText(/Ethereum · Exchange/)).toBeVisible()
    expect(within(lista).getByText('0x5290…9ee7')).toBeVisible()
    expect(within(lista).getByText('Última leitura em 22/10/2026 às 12:30')).toBeVisible()

    fireEvent.click(within(lista).getByRole('button', { name: /Mostrar endereço completo de Cofre/ }))
    expect(within(lista).getByText(ENDERECO)).toBeVisible()
  })

  it('diz Ainda não lida quando nunca sincronizou', async () => {
    mockFetch({ 'GET /carteiras': () => json([{ ...carteira, sincronizadaEm: null, saldos: [] }]) })
    renderizar()
    expect(await screen.findByText('Ainda não lida')).toBeVisible()
  })

  it('leitura que falhou avisa com a data e mantém o saldo', async () => {
    mockFetch({ 'GET /carteiras': () => json([{ ...carteira, leituraFalhou: true }]) })
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Carteiras' })
    expect(within(lista).getByText('Não consegui atualizar agora, mostrando o saldo de 22/10/2026 às 12:30.')).toBeVisible()
    expect(within(lista).getByText('1,5 ETH')).toBeVisible()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('sincroniza e troca a carteira pela resposta', async () => {
    const lida = { ...carteira, sincronizadaEm: '2026-10-22T18:00:00Z' }
    mockFetch({ 'POST /carteiras/w1/sincronizar': () => json(lida) })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Sincronizar Cofre' }))
    expect(await screen.findByText('Última leitura em 22/10/2026 às 15:00')).toBeVisible()
  })

  it('mostra erro quando a sincronização falha de verdade', async () => {
    mockFetch({ 'POST /carteiras/w1/sincronizar': () => json({ erro: 'x' }, 500) })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Sincronizar Cofre' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível sincronizar')
  })

  it('remove só depois de confirmar', async () => {
    mockFetch({ 'DELETE /carteiras/w1': () => new Response(null, { status: 204 }) })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Remover Cofre' }))
    expect(chamadas('DELETE /carteiras/w1')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: /Confirmar remoção de Cofre/ }))
    await waitFor(() => expect(chamadas('DELETE /carteiras/w1')).toHaveLength(1))
  })

  it('ativo não confiável mostra selo, aviso e promove com o corpo completo', async () => {
    mockFetch({ 'PUT /ativos-cripto/a2': () => json({ ...golpe, confiavel: true }) })
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Carteiras' })
    expect(within(lista).getByText('1.000.000,000001 SCAM')).toBeVisible()
    expect(within(lista).getByText('Não confiável')).toBeVisible()
    expect(within(lista).getByText(/fora do patrimônio até você promover/)).toBeVisible()

    fireEvent.click(within(lista).getByRole('button', { name: 'Promover SCAM para confiável' }))
    await waitFor(() => expect(chamadas('PUT /ativos-cripto/a2')).toHaveLength(1))
    expect(chamadas('PUT /ativos-cripto/a2')[0]).toEqual({ simbolo: 'SCAM', decimais: 6, nome: null, confiavel: true })
  })

  it('custo pendente pede o custo em dólar e envia em centavos', async () => {
    mockFetch({ 'PUT /movimentos-cripto/m1/custo': () => json({ ...entradaSemCusto, custoUsdCentavos: 450_000 }) })
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Carteiras' })
    expect(within(lista).getByText(/Falta o custo em dólar de ETH/)).toBeVisible()
    fireEvent.change(within(lista).getByLabelText(/Custo em dólar da entrada/), { target: { value: '4.500,00' } })
    fireEvent.click(within(lista).getByRole('button', { name: 'Salvar custo' }))
    await waitFor(() => expect(chamadas('PUT /movimentos-cripto/m1/custo')).toHaveLength(1))
    expect(chamadas('PUT /movimentos-cripto/m1/custo')[0]).toEqual({ custoUsdCentavos: 450_000 })
  })

  it('recusa custo inválido sem chamar a API', async () => {
    mockFetch()
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Carteiras' })
    fireEvent.change(within(lista).getByLabelText(/Custo em dólar da entrada/), { target: { value: '0' } })
    fireEvent.click(within(lista).getByRole('button', { name: 'Salvar custo' }))
    expect(await within(lista).findByRole('alert')).toHaveTextContent('Valor inválido')
    expect(chamadas('PUT /movimentos-cripto/m1/custo')).toHaveLength(0)
  })

  it('movimento sincronizado sem custo aparece sem quebrar e sem botão de apagar', async () => {
    mockFetch()
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Movimentos' })
    expect(within(lista).getByText('Entrada sincronizada de 1,5 ETH')).toBeVisible()
    expect(within(lista).getByText('—')).toBeVisible()
    expect(within(lista).queryByRole('button', { name: /Apagar/ })).toBeNull()
  })

  it('posição com custo pendente e ativo não confiável mostra os avisos em vez de null', async () => {
    const posicao = {
      contaId: 'c1',
      ativoId: 'a2',
      quantidade: '10',
      confiavel: false,
      custoPendente: true,
      custoUsdCentavos: null,
      custoCentavos: null,
      cotacao: null,
      valorUsdCentavos: null,
      valorCentavos: null,
      custoMedioUsd: null,
      custoDaPosicaoUsdCentavos: null,
      custoDaPosicaoCentavos: null,
      ganhoUsdCentavos: null,
      ganhoCentavos: null,
      realizadoUsdCentavos: 0,
      realizadoCentavos: 0,
    }
    mockFetch({ 'GET /posicoes-cripto?data=2026-10-22': () => json({ ...posicoesVazias, posicoes: [posicao] }) })
    renderizar()
    const lista = await screen.findByRole('list', { name: 'Posições' })
    expect(within(lista).getByText(/Custo pendente/)).toBeVisible()
    expect(within(lista).getByText('Não confiável')).toBeVisible()
  })

  it('erro ao carregar as carteiras aparece e não vira lista vazia', async () => {
    mockFetch({ 'GET /carteiras': () => json({ erro: 'x' }, 500) })
    renderizar()
    expect(await screen.findByText('Não foi possível carregar as carteiras.')).toBeVisible()
    expect(screen.queryByText('Nenhuma carteira observada.')).toBeNull()
  })

  it('sessão expirada ao listar avisa o app', async () => {
    mockFetch({ 'GET /carteiras': () => json({ erro: 'x' }, 401) })
    const { aoSessaoExpirar } = renderizar()
    await waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })
})
