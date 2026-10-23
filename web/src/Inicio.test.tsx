import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import App from './App'

type Rotas = Partial<Record<string, (corpo: unknown) => Promise<Response>>>

const usuario = { id: '7f1e2c9a-0b6d-4f43-9a52-1c8d3e5b6a70', email: 'ana@exemplo.com', nome: 'Ana' }

function conta(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: 'c1',
    nome: 'Nubank',
    tipo: 'corrente',
    moeda: 'BRL',
    saldoInicialCentavos: 0,
    saldoCentavos: 100000,
    arquivada: false,
    criadaEm: '2026-10-19T12:00:00Z',
    cartao: null,
    categoriasPermitidas: null,
    ...sobra,
  }
}

const visa = conta({
  id: 'c9',
  nome: 'Visa',
  tipo: 'cartao_credito',
  saldoCentavos: -20000,
  cartao: { limiteCentavos: 500000, diaFechamento: 31, diaVencimento: 10, contaPagamentoId: 'c1', disponivelCentavos: 480000 },
})

function prevista(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: 'o1',
    tipo: 'despesa',
    status: 'previsto',
    titulo: 'Aluguel',
    descricao: null,
    valorCentavos: 150000,
    categoriaId: 'cd1',
    transferenciaId: null,
    templateId: 'r1',
    parcelaOrdem: null,
    compraParceladaId: null,
    parcelaNum: null,
    parcelaTotal: null,
    direcao: null,
    contaId: 'c1',
    dataCompetencia: '2026-10-25',
    dataCaixa: '2026-10-25',
    criadoEm: '2026-10-05T12:00:00Z',
    ...sobra,
  }
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

const responde = (corpo: unknown, status = 200) => () => Promise.resolve(jsonResponse(corpo, status))

const saldoProjetadoPadrao = {
  inicio: '2026-10-19',
  fim: '2026-10-19',
  contaId: null,
  saldoAtualCentavos: 0,
  vencidasCentavos: 0,
  menorSaldo: { data: '2026-10-19', saldoCentavos: 0 },
  dias: [{ data: '2026-10-19', entradasCentavos: 0, saidasCentavos: 0, saldoCentavos: 0 }],
}

// Hoje é 19/10/2026: as previstas dos próximos 30 dias vão até 18/11.
const PROXIMAS = 'GET /lancamentos?de=2026-10-19&ate=2026-11-18'

function mockFetch(rotas: Rotas): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const rota = rotas[chave]
      if (rota) return rota(init?.body)
      if (chave === 'GET /auth/eu') return Promise.resolve(jsonResponse(usuario, 200))
      if (chave === 'GET /healthz') return Promise.resolve(jsonResponse({ mensagem: 'ok' }, 200))
      if (chave === 'GET /categorias' || chave === 'GET /recorrencias') return Promise.resolve(jsonResponse([], 200))
      if (chave.startsWith('GET /lancamentos?')) return Promise.resolve(jsonResponse([], 200))
      if (chave === 'GET /lancamentos/vencidos') {
        return Promise.resolve(jsonResponse({ lancamentos: [], maisAlgum: false }, 200))
      }
      if (chave.startsWith('GET /saldo-projetado?')) return Promise.resolve(jsonResponse(saldoProjetadoPadrao, 200))
      if (chave.startsWith('GET /orcamentos?')) return Promise.resolve(jsonResponse({ mes: url.slice(-7), orcamentos: [] }, 200))
      if (chave === 'GET /metas') return Promise.resolve(jsonResponse([], 200))
      return Promise.reject(new Error(`rota sem mock: ${chave}`))
    }),
  )
}

function chamadasA(chave: string): number {
  return vi.mocked(fetch).mock.calls.filter(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}` === chave).length
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replaceAll(' ', '[\\s\\u00a0]'))

describe('Início', () => {
  beforeEach(() => {
    window.location.hash = '#/inicio'
    // Só o Date: com os timers falsos o waitFor do testing-library travaria.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 19, 23, 30))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  describe('resumo das contas', () => {
    it('soma o saldo das contas ativas e o disponível dos cartões', async () => {
      mockFetch({ 'GET /contas': responde([conta(), visa]) })
      render(<App />)
      const resumo = within(await screen.findByRole('region', { name: 'Resumo das contas' }))
      // 1.000,00 da conta corrente menos 200,00 de fatura em aberto.
      expect(await resumo.findByText(texto('R$ 800,00'))).toBeVisible()
      expect(resumo.getByText('Disponível nos cartões').nextSibling).toHaveTextContent(texto('R$ 4.800,00'))
      expect(resumo.getByText('Contas ativas').nextSibling).toHaveTextContent('2')
    })

    it('sem cartão não fala de disponível', async () => {
      mockFetch({ 'GET /contas': responde([conta()]) })
      render(<App />)
      const resumo = within(await screen.findByRole('region', { name: 'Resumo das contas' }))
      expect(await resumo.findByText(texto('R$ 1.000,00'))).toBeVisible()
      expect(resumo.queryByText('Disponível nos cartões')).toBeNull()
    })

    it('mostra carregando enquanto as contas não chegam', async () => {
      mockFetch({ 'GET /contas': () => new Promise<Response>(() => {}) })
      render(<App />)
      expect(await screen.findByText('Carregando o resumo das contas...')).toBeVisible()
    })

    it('sem conta convida a criar a primeira e aponta para Contas', async () => {
      mockFetch({ 'GET /contas': responde([]) })
      render(<App />)
      expect(await screen.findByText('Cadastre sua primeira conta.')).toBeVisible()
      expect(screen.getByRole('link', { name: 'Ir para Contas' })).toHaveAttribute('href', '#/contas')
    })
  })

  describe('benefícios', () => {
    const vr = conta({ id: 'b1', nome: 'VR', tipo: 'beneficio', saldoCentavos: 80000, categoriasPermitidas: ['cd1'] })
    const va = conta({ id: 'b2', nome: 'VA', tipo: 'beneficio', saldoCentavos: 25050, categoriasPermitidas: [] })

    it('tira o benefício de "Saldo nas contas" e mostra o saldo somado num cartão próprio', async () => {
      mockFetch({ 'GET /contas': responde([conta(), vr, va]) })
      render(<App />)
      const resumo = within(await screen.findByRole('region', { name: 'Resumo das contas' }))
      // Só os 1.000,00 da corrente: os 800,00 do VR e os 250,50 do VA ficam de fora.
      expect(await resumo.findByText(texto('R$ 1.000,00'))).toBeVisible()
      expect(resumo.getByText('Contas ativas').nextSibling).toHaveTextContent('1')
      const beneficios = within(screen.getByRole('region', { name: 'Benefícios' }))
      expect(beneficios.getByText(texto('R$ 1.050,50'), { selector: 'p' })).toBeVisible()
      const itens = beneficios.getAllByRole('listitem')
      expect(itens[0]).toHaveTextContent('VR')
      expect(itens[0]).toHaveTextContent(texto('R$ 800,00'))
      expect(itens[1]).toHaveTextContent('VA')
      expect(itens[1]).toHaveTextContent(texto('R$ 250,50'))
    })

    it('sem conta de benefício não mostra o cartão', async () => {
      mockFetch({ 'GET /contas': responde([conta()]) })
      render(<App />)
      const resumo = within(await screen.findByRole('region', { name: 'Resumo das contas' }))
      expect(await resumo.findByText(texto('R$ 1.000,00'))).toBeVisible()
      expect(screen.queryByRole('region', { name: 'Benefícios' })).toBeNull()
    })

    it('benefício com saldo negativo aparece com o sinal', async () => {
      mockFetch({ 'GET /contas': responde([conta(), conta({ id: 'b3', nome: 'VR', tipo: 'beneficio', saldoCentavos: -1000, categoriasPermitidas: [] })]) })
      render(<App />)
      const beneficios = within(await screen.findByRole('region', { name: 'Benefícios' }))
      expect(beneficios.getByText(texto('-R$ 10,00'), { selector: 'p' })).toBeVisible()
    })
  })

  describe('próximas previstas', () => {
    it('lista o que ainda vai mover dinheiro, por data, com sinal, conta e valor', async () => {
      mockFetch({
        'GET /contas': responde([conta()]),
        [PROXIMAS]: responde([
          prevista({ id: 'o2', titulo: 'Salário', tipo: 'receita', valorCentavos: 500000, dataCaixa: '2026-11-05' }),
          prevista(),
          // Realizado hoje já moveu dinheiro; o de caixa futura (compra no cartão) ainda vai mover.
          prevista({ id: 'r8', titulo: 'Padaria', status: 'realizado', dataCaixa: '2026-10-19' }),
          prevista({ id: 'r9', titulo: 'Tênis', status: 'realizado', valorCentavos: 30000, dataCaixa: '2026-10-28' }),
          prevista({ id: 'c3', titulo: 'Velha', status: 'cancelado', dataCaixa: '2026-10-21' }),
        ]),
      })
      render(<App />)
      const lista = await screen.findByRole('list', { name: 'Próximas previstas' })
      // O nome da conta chega com a lista de contas, que pode vir depois das previstas.
      await within(lista).findAllByText('Nubank')
      const itens = within(lista).getAllByRole('listitem')
      expect(itens).toHaveLength(3)
      expect(itens[0]).toHaveTextContent('25/10')
      expect(itens[0]).toHaveTextContent('Aluguel')
      expect(itens[0]).toHaveTextContent('Nubank')
      expect(itens[0]).toHaveTextContent(texto('− R$ 1.500,00'))
      expect(itens[1]).toHaveTextContent('Tênis')
      expect(itens[2]).toHaveTextContent('Salário')
      expect(itens[2]).toHaveTextContent(texto('+ R$ 5.000,00'))
      expect(screen.getByRole('link', { name: 'Ver todos os lançamentos' })).toHaveAttribute('href', '#/lancamentos')
    })

    it('mostra no máximo seis', async () => {
      const muitas = Array.from({ length: 8 }, (_, i) => prevista({ id: `o${i}`, titulo: `Conta ${i}` }))
      mockFetch({ 'GET /contas': responde([conta()]), [PROXIMAS]: responde(muitas) })
      render(<App />)
      const lista = await screen.findByRole('list', { name: 'Próximas previstas' })
      expect(within(lista).getAllByRole('listitem')).toHaveLength(6)
    })

    it('sem previstas diz isso e aponta para as recorrências', async () => {
      mockFetch({ 'GET /contas': responde([conta()]), [PROXIMAS]: responde([]) })
      render(<App />)
      expect(await screen.findByText('Nada previsto para os próximos 30 dias.')).toBeVisible()
      expect(screen.getByRole('link', { name: 'Ver recorrências' })).toHaveAttribute('href', '#/recorrencias')
    })

    it('mostra carregando enquanto a lista não chega', async () => {
      mockFetch({ 'GET /contas': responde([conta()]), [PROXIMAS]: () => new Promise<Response>(() => {}) })
      render(<App />)
      expect(await screen.findByText('Carregando as próximas previstas...')).toBeVisible()
    })

    it('erro avisa e "Tentar de novo" busca outra vez', async () => {
      let chamadas = 0
      mockFetch({
        'GET /contas': responde([conta()]),
        [PROXIMAS]: () => {
          chamadas += 1
          return Promise.resolve(chamadas === 1 ? jsonResponse({ erro: 'x' }, 500) : jsonResponse([prevista()], 200))
        },
      })
      render(<App />)
      const alerta = await screen.findByRole('alert')
      expect(alerta).toHaveTextContent('Não foi possível carregar as próximas previstas.')
      fireEvent.click(screen.getByRole('button', { name: /tentar de novo/i }))
      expect(await screen.findByRole('list', { name: 'Próximas previstas' })).toBeVisible()
      expect(chamadasA(PROXIMAS)).toBe(2)
    })

    it('503 mostra serviço indisponível', async () => {
      mockFetch({ 'GET /contas': responde([conta()]), [PROXIMAS]: responde({ erro: 'x' }, 503) })
      render(<App />)
      expect(await screen.findByRole('alert')).toHaveTextContent('Serviço indisponível')
    })

    it('401 volta para o login com o aviso', async () => {
      mockFetch({ 'GET /contas': responde([conta()]), [PROXIMAS]: responde({ erro: 'x' }, 401) })
      render(<App />)
      expect(await screen.findByRole('form', { name: /entrar/i })).toBeVisible()
      await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(/sessão acabou/i))
    })
  })

  describe('orçamento do mês', () => {
    function orcamento(nome: string, teto: number, gasto: number): Record<string, unknown> {
      return {
        categoriaId: nome,
        categoriaNome: nome,
        tetoCentavos: teto,
        desde: '2026-10',
        gastoCentavos: gasto,
        previstoCentavos: 0,
        restanteCentavos: teto - gasto,
      }
    }

    it('mostra os três tetos mais consumidos e quantos passaram', async () => {
      mockFetch({
        'GET /orcamentos?mes=2026-10': responde({
          mes: '2026-10',
          orcamentos: [
            orcamento('Lazer', 10_000, 1_000),
            orcamento('Mercado', 80_000, 85_000),
            orcamento('Transporte', 20_000, 15_000),
            orcamento('Saúde', 50_000, 20_000),
          ],
        }),
      })
      render(<App />)

      const cartao = await screen.findByRole('region', { name: 'Orçamento de outubro de 2026' })
      const itens = within(await within(cartao).findByRole('list', { name: 'Tetos do mês' })).getAllByRole('listitem')
      expect(itens).toHaveLength(3)
      expect(itens[0]).toHaveTextContent('Mercado')
      expect(itens[1]).toHaveTextContent('Transporte')
      expect(itens[2]).toHaveTextContent('Saúde')
      expect(itens[0]).toHaveTextContent(texto('R$ 850,00 de R$ 800,00'))
      expect(within(cartao).getByText('1 categoria acima do teto')).toBeVisible()
      expect(within(cartao).getByRole('link', { name: 'Ver orçamento' })).toHaveAttribute('href', '#/orcamento')
    })

    it('sem teto no mês, leva para definir', async () => {
      mockFetch({})
      render(<App />)

      const cartao = await screen.findByRole('region', { name: 'Orçamento de outubro de 2026' })
      expect(await within(cartao).findByText('Nenhum teto neste mês.')).toBeVisible()
      expect(within(cartao).getByRole('link', { name: 'Definir tetos' })).toHaveAttribute('href', '#/orcamento')
    })
  })

  describe('metas', () => {
    function meta(id: string, titulo: string, atingida: boolean): Record<string, unknown> {
      return {
        id,
        titulo,
        valorAlvoCentavos: 100_000,
        dataAlvo: null,
        aporteMensalCentavos: null,
        acumuladoCentavos: atingida ? 100_000 : 40_000,
        faltaCentavos: atingida ? 0 : 60_000,
        atingida,
        prazoVencido: false,
        necessarioPorMesCentavos: null,
        chegadaPeloPlano: null,
        ritmoMensalCentavos: 0,
        chegadaPeloRitmo: null,
      }
    }

    it('mostra até três metas, as abertas primeiro, e leva para a tela', async () => {
      mockFetch({
        'GET /metas': responde([
          meta('m1', 'Celular', true),
          meta('m2', 'Viagem', false),
          meta('m3', 'Carro', false),
          meta('m4', 'Casa', false),
        ]),
      })
      render(<App />)

      const cartao = await screen.findByRole('region', { name: 'Metas' })
      const itens = within(await within(cartao).findByRole('list', { name: 'Metas em andamento' })).getAllByRole('listitem')
      expect(itens).toHaveLength(3)
      expect(itens[0]).toHaveTextContent('Viagem')
      expect(itens[0]).toHaveTextContent(texto('R$ 400,00 de R$ 1.000,00'))
      expect(within(cartao).queryByText('Celular')).toBeNull()
      expect(within(cartao).getByRole('link', { name: 'Ver metas' })).toHaveAttribute('href', '#/metas')
    })

    it('sem meta, leva para criar', async () => {
      mockFetch({})
      render(<App />)

      const cartao = await screen.findByRole('region', { name: 'Metas' })
      expect(await within(cartao).findByText('Nenhuma meta ainda.')).toBeVisible()
      expect(within(cartao).getByRole('link', { name: 'Criar meta' })).toHaveAttribute('href', '#/metas')
    })
  })
})
