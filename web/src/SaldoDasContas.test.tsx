import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { Conta, Patrimonio } from './api'
import SaldoDasContas, { valorDaVisao } from './SaldoDasContas'

function conta(tipo: Conta['tipo'], saldoCentavos: number): Conta {
  return {
    id: `${tipo}-${saldoCentavos}`,
    nome: tipo,
    tipo,
    moeda: 'BRL',
    saldoInicialCentavos: saldoCentavos,
    saldoCentavos,
    arquivada: false,
    criadaEm: '2026-10-19T12:00:00Z',
    cartao: null,
    categoriasPermitidas: null,
    percentualCdi: null,
    emInvestimentos: false,
  }
}

function patrimonio(cripto: number, rendaFixa: number): Patrimonio {
  return { criptoCentavos: cripto, rendaFixaCentavos: rendaFixa } as Patrimonio
}

describe('valorDaVisao', () => {
  it('soma só os tipos da visão', () => {
    const contas = [conta('corrente', 100_00), conta('poupanca', 50_00), conta('cartao_credito', -300_00)]

    expect(valorDaVisao(contas, null, ['corrente', 'poupanca'])).toBe(150_00n)
    expect(valorDaVisao(contas, null, ['cartao_credito'])).toBe(-300_00n)
  })

  it('traz cripto e investimentos do patrimônio', () => {
    const contas = [conta('corrente', 100_00)]

    expect(valorDaVisao(contas, patrimonio(40_00, 60_00), ['corrente', 'cripto', 'investimento'])).toBe(200_00n)
    expect(valorDaVisao(contas, null, ['cripto'])).toBe(0n)
  })

  it('soma sem perder centavo acima do inteiro seguro', () => {
    const contas = [conta('corrente', Number.MAX_SAFE_INTEGER), conta('poupanca', Number.MAX_SAFE_INTEGER)]

    expect(valorDaVisao(contas, null, ['corrente', 'poupanca'])).toBe(BigInt(Number.MAX_SAFE_INTEGER) * 2n)
  })
})

type Visao = { id: string; titulo: string; tipos: string[] }

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

// texto sem o espaço sem quebra do Intl
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replaceAll(' ', '[\\s\\u00a0]'))

function mockFetch(visoes: Visao[], patrimonio: unknown = null): { chamadas: { metodo: string; url: string; corpo: unknown }[] } {
  let atuais = visoes
  const chamadas: { metodo: string; url: string; corpo: unknown }[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const metodo = init?.method ?? 'GET'
      const corpo = init?.body === undefined ? null : (JSON.parse(String(init.body)) as unknown)
      chamadas.push({ metodo, url, corpo })
      if (url === '/visoes-saldo' && metodo === 'GET') return Promise.resolve(jsonResponse(atuais, 200))
      if (url === '/visoes-saldo' && metodo === 'POST') {
        const dados = corpo as { titulo: string; tipos: string[] }
        atuais = [...atuais, { id: `v${atuais.length + 1}`, titulo: dados.titulo, tipos: dados.tipos }]
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      if (url.startsWith('/visoes-saldo/') && metodo === 'PUT') {
        const dados = corpo as { titulo: string; tipos: string[] }
        atuais = atuais.map((v) => (v.id === url.split('/').pop() ? { ...v, ...dados } : v))
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      if (url.startsWith('/visoes-saldo/') && metodo === 'DELETE') {
        atuais = atuais.filter((v) => v.id !== url.split('/').pop())
        return Promise.resolve(new Response(null, { status: 204 }))
      }
      if (url.startsWith('/patrimonio')) {
        return patrimonio === null ? new Promise<Response>(() => {}) : Promise.resolve(jsonResponse(patrimonio, 200))
      }
      return Promise.resolve(jsonResponse({ erro: 'inesperado' }, 500))
    }),
  )
  return { chamadas }
}

const PADRAO: Visao = { id: 'v1', titulo: 'Total sem cartão', tipos: ['corrente', 'poupanca', 'dinheiro'] }
const contas = [conta('corrente', 100_00), conta('poupanca', 50_00), conta('dinheiro', 5_00), conta('cartao_credito', -30_00)]

function renderizar() {
  return render(<SaldoDasContas contas={contas} aoSessaoExpirar={vi.fn()} />)
}

describe('SaldoDasContas', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('mostra um cartão por visão, com o valor dos tipos dela', async () => {
    mockFetch([PADRAO, { id: 'v2', titulo: 'Só a corrente', tipos: ['corrente'] }])
    renderizar()

    const grupo = await screen.findByRole('group', { name: 'Totais das contas' })
    expect((await within(grupo).findByText('Total sem cartão')).parentElement).toHaveTextContent(texto('R$ 155,00'))
    expect(within(grupo).getByText('Só a corrente').parentElement).toHaveTextContent(texto('R$ 100,00'))
  })

  it('visão com cripto mostra o dólar da cripto logo abaixo do total em reais', async () => {
    mockFetch([{ id: 'v3', titulo: 'Cripto', tipos: ['cripto'] }], {
      data: '2026-10-22',
      contasCentavos: 0,
      criptoCentavos: 4_320_000,
      criptoUsdCentavos: 864_000,
      criptoAportadoCentavos: null,
      criptoValorizacaoCentavos: null,
      rendaFixaCentavos: 0,
      totalCentavos: 4_320_000,
      semCotacao: [],
    })
    renderizar()

    const grupo = await screen.findByRole('group', { name: 'Totais das contas' })
    const real = await within(grupo).findByText(texto('R$ 43.200,00'))
    const dolar = await within(grupo).findByText(texto('Cripto em dólar US$ 8.640,00'))
    expect(real.compareDocumentPosition(dolar) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('sem o dólar da cripto, não mostra a linha em dólar', async () => {
    mockFetch([{ id: 'v3', titulo: 'Cripto', tipos: ['cripto'] }], {
      data: '2026-10-22',
      contasCentavos: 0,
      criptoCentavos: 4_320_000,
      criptoUsdCentavos: null,
      criptoAportadoCentavos: null,
      criptoValorizacaoCentavos: null,
      rendaFixaCentavos: 0,
      totalCentavos: 4_320_000,
      semCotacao: [],
    })
    renderizar()

    const grupo = await screen.findByRole('group', { name: 'Totais das contas' })
    await within(grupo).findByText(texto('R$ 43.200,00'))
    expect(within(grupo).queryByText(/Cripto em dólar/)).toBeNull()
  })

  it('a engrenagem lista as visões e adiciona uma nova', async () => {
    const { chamadas } = mockFetch([PADRAO])
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: 'Configurar os saldos' }))
    fireEvent.click(screen.getByRole('button', { name: /adicionar visão/i }))
    fireEvent.change(screen.getByLabelText('Título'), { target: { value: 'Reserva' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Poupança' }))
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    await waitFor(() =>
      expect(chamadas.find((c) => c.metodo === 'POST')?.corpo).toEqual({ titulo: 'Reserva', tipos: ['poupanca'] }),
    )
    const grupo = await screen.findByRole('group', { name: 'Totais das contas' })
    expect((await within(grupo).findByText('Reserva')).parentElement).toHaveTextContent(texto('R$ 50,00'))
  })

  it('edita título e tipos, inclusive da visão padrão', async () => {
    const { chamadas } = mockFetch([PADRAO])
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: 'Configurar os saldos' }))
    fireEvent.click(screen.getByRole('button', { name: 'Editar Total sem cartão' }))
    fireEvent.change(screen.getByLabelText('Título'), { target: { value: 'Tudo' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Dinheiro' }))
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    await waitFor(() =>
      expect(chamadas.find((c) => c.metodo === 'PUT')?.corpo).toEqual({
        titulo: 'Tudo',
        tipos: ['corrente', 'poupanca'],
      }),
    )
  })

  it('exclui uma visão depois da confirmação, e a última não pode ser excluída', async () => {
    const { chamadas } = mockFetch([PADRAO, { id: 'v2', titulo: 'Extra', tipos: ['corrente'] }])
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: 'Configurar os saldos' }))
    fireEvent.click(screen.getByRole('button', { name: 'Excluir Extra' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar excluir Extra' }))

    await waitFor(() => expect(chamadas.some((c) => c.metodo === 'DELETE' && c.url === '/visoes-saldo/v2')).toBe(true))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Excluir Total sem cartão' })).toBeDisabled())
  })

  it('não deixa salvar sem nenhum tipo', async () => {
    const { chamadas } = mockFetch([PADRAO])
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: 'Configurar os saldos' }))
    fireEvent.click(screen.getByRole('button', { name: /adicionar visão/i }))
    fireEvent.change(screen.getByLabelText('Título'), { target: { value: 'Vazia' } })
    fireEvent.click(screen.getByRole('button', { name: 'Salvar' }))

    expect(await screen.findByText('Escolha ao menos um tipo de conta.')).toBeVisible()
    expect(chamadas.some((c) => c.metodo === 'POST')).toBe(false)
  })

  it('o botão de adicionar trava no limite de 10 visões', async () => {
    mockFetch(Array.from({ length: 10 }, (_, i) => ({ id: `v${i}`, titulo: `Visão ${i}`, tipos: ['corrente'] })))
    renderizar()

    fireEvent.click(await screen.findByRole('button', { name: 'Configurar os saldos' }))

    expect(screen.getByRole('button', { name: /adicionar visão/i })).toBeDisabled()
  })
})
