import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import App from './App'
import { acao } from './testeAcoes'

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

function cartaoVisa(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return conta({
    id: 'c9',
    nome: 'Visa',
    tipo: 'cartao_credito',
    saldoCentavos: -20000,
    cartao: {
      limiteCentavos: 500000,
      diaFechamento: 31,
      diaVencimento: 10,
      contaPagamentoId: 'c1',
      disponivelCentavos: 480000,
    },
    ...sobra,
  })
}

function fatura(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    vencimento: '2026-11-10',
    fechamento: '2026-10-31',
    totalCentavos: 20000,
    pagoCentavos: 0,
    restanteCentavos: 20000,
    situacao: 'fechada',
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

function mockFetch(rotas: Rotas): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const rota = rotas[chave]
      if (rota) return rota(init?.body)
      if (chave === 'GET /healthz') return Promise.resolve(jsonResponse({ mensagem: 'ok' }, 200))
      if (chave === 'GET /categorias') {
        return Promise.resolve(
          jsonResponse([{ id: 'cd1', nome: 'Mercado', tipo: 'despesa', paiId: null, cor: null, icone: null }], 200),
        )
      }
      if (chave === 'GET /recorrencias') return Promise.resolve(jsonResponse([], 200))
      if (chave.startsWith('GET /lancamentos?')) return Promise.resolve(jsonResponse([], 200))
      if (chave === 'GET /lancamentos/vencidos') {
        return Promise.resolve(jsonResponse({ lancamentos: [], maisAlgum: false }, 200))
      }
      if (chave.startsWith('GET /saldo-projetado?')) return Promise.resolve(jsonResponse(saldoProjetadoPadrao, 200))
      return Promise.reject(new Error(`rota sem mock: ${chave}`))
    }),
  )
}

function chamadasA(chave: string): [string, RequestInit | undefined][] {
  return vi
    .mocked(fetch)
    .mock.calls.map(([url, init]): [string, RequestInit | undefined] => [String(url), init])
    .filter(([url, init]) => `${init?.method ?? 'GET'} ${url}` === chave)
}

const sessaoViva = { 'GET /auth/eu': responde(usuario) }
const FATURAS = 'GET /contas/c9/faturas?de=2026-07-01&ate=2027-04-30'

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replaceAll(' ', '[\\s\\u00a0]'))

const secaoContas = (): HTMLElement => screen.getByRole('region', { name: /^contas/i })
const novaConta = (): HTMLElement => within(secaoContas()).getByRole('form', { name: 'Nova conta' })

// Os formulários de criar abrem em painéis, pelos botões do cabeçalho de cada seção (ADR-033).
async function abrirPainel(botao: string, formulario: string): Promise<HTMLElement> {
  const abrir = await screen.findByRole('button', { name: botao })
  await waitFor(() => expect(abrir).toBeEnabled())
  fireEvent.click(abrir)
  return screen.findByRole('form', { name: formulario })
}

async function escolherCartao(): Promise<HTMLElement> {
  await abrirPainel('Nova conta', 'Nova conta')
  const form = novaConta()
  fireEvent.change(within(form).getByLabelText('Tipo'), { target: { value: 'cartao_credito' } })
  return form
}

async function abrirFaturas(): Promise<void> {
  fireEvent.click(await screen.findByRole('button', { name: 'Faturas de Visa' }))
}

// As seções ficam atrás de rota por hash (ADR-033): o cartão mora em Contas; o bloco de recorrência troca de seção.
beforeEach(() => {
  window.location.hash = '#/contas'
})

describe('Cartão de crédito', () => {
  beforeEach(() => {
    // Só o Date: com os timers falsos o waitFor do testing-library travaria.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 19, 23, 30))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  describe('nova conta', () => {
    it('envia limite em centavos, dias, conta de pagamento e a fatura em aberto como saldo negativo', async () => {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta()]),
        'POST /contas': responde(cartaoVisa(), 201),
      })
      render(<App />)
      await within(await screen.findByRole('region', { name: /^contas/i })).findByText('Nubank')
      const form = await escolherCartao()
      fireEvent.change(within(form).getByLabelText('Nome'), { target: { value: 'Visa' } })
      expect(within(form).getByText('Fatura em aberto hoje (opcional)')).toBeVisible()
      fireEvent.change(within(form).getByLabelText('Fatura em aberto hoje (opcional)'), {
        target: { value: '200,00' },
      })
      fireEvent.change(within(form).getByLabelText('Limite em reais'), { target: { value: '5.000,00' } })
      fireEvent.change(within(form).getByLabelText('Dia de fechamento'), { target: { value: '31' } })
      fireEvent.change(within(form).getByLabelText('Dia de vencimento'), { target: { value: '10' } })
      expect(within(form).getByText(/meses curtos usam o último dia/i)).toBeVisible()
      fireEvent.change(within(form).getByLabelText('Conta de pagamento'), { target: { value: 'c1' } })
      fireEvent.submit(form)
      await waitFor(() => expect(chamadasA('POST /contas')).toHaveLength(1))
      expect(JSON.parse(String(chamadasA('POST /contas')[0][1]?.body))).toEqual({
        nome: 'Visa',
        tipo: 'cartao_credito',
        saldoInicialCentavos: -20000,
        cartao: { limiteCentavos: 500000, diaFechamento: 31, diaVencimento: 10, contaPagamentoId: 'c1' },
      })
      expect(await within(secaoContas()).findByText('Visa')).toBeVisible()
    })

    it('sem conta corrente ou poupança avisa e não deixa criar', async () => {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta({ id: 'c2', nome: 'Carteira', tipo: 'dinheiro' })]),
      })
      render(<App />)
      await within(await screen.findByRole('region', { name: /^contas/i })).findByText('Carteira')
      const form = await escolherCartao()
      expect(within(form).getByText('Crie uma conta corrente ou poupança para pagar o cartão.')).toBeVisible()
      expect(within(form).getByRole('button', { name: 'Criar conta' })).toBeDisabled()
      expect(within(form).queryByLabelText('Conta de pagamento')).not.toBeInTheDocument()
      fireEvent.submit(form)
      expect(chamadasA('POST /contas')).toHaveLength(0)
    })

    it('limite ou dia inválido não chega à API', async () => {
      mockFetch({ ...sessaoViva, 'GET /contas': responde([conta()]) })
      render(<App />)
      await within(await screen.findByRole('region', { name: /^contas/i })).findByText('Nubank')
      const form = await escolherCartao()
      fireEvent.change(within(form).getByLabelText('Nome'), { target: { value: 'Visa' } })
      fireEvent.change(within(form).getByLabelText('Limite em reais'), { target: { value: '0' } })
      fireEvent.change(within(form).getByLabelText('Dia de fechamento'), { target: { value: '5' } })
      fireEvent.change(within(form).getByLabelText('Dia de vencimento'), { target: { value: '10' } })
      fireEvent.submit(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent(/limite inválido/i)
      fireEvent.change(within(form).getByLabelText('Limite em reais'), { target: { value: '100,00' } })
      fireEvent.change(within(form).getByLabelText('Dia de vencimento'), { target: { value: '32' } })
      fireEvent.submit(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent(/1 a 31/)
      expect(chamadasA('POST /contas')).toHaveLength(0)
    })

    it('400 mostra a mensagem do servidor', async () => {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta()]),
        'POST /contas': responde({ erro: 'conta de pagamento não encontrada' }, 400),
      })
      render(<App />)
      await within(await screen.findByRole('region', { name: /^contas/i })).findByText('Nubank')
      const form = await escolherCartao()
      fireEvent.change(within(form).getByLabelText('Nome'), { target: { value: 'Visa' } })
      fireEvent.change(within(form).getByLabelText('Limite em reais'), { target: { value: '100,00' } })
      fireEvent.change(within(form).getByLabelText('Dia de fechamento'), { target: { value: '5' } })
      fireEvent.change(within(form).getByLabelText('Dia de vencimento'), { target: { value: '10' } })
      fireEvent.submit(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent('conta de pagamento não encontrada')
    })
  })

  describe('lista de contas', () => {
    it('mostra saldo, limite, disponível e o botão Faturas', async () => {
      mockFetch({ ...sessaoViva, 'GET /contas': responde([conta(), cartaoVisa()]) })
      render(<App />)
      const lista = await within(await screen.findByRole('region', { name: /^contas/i })).findByRole('list')
      const visa = within(lista).getByText('Visa').closest('li')
      expect(visa).not.toBeNull()
      expect(visa?.textContent).toMatch(texto('-R$ 200,00'))
      expect(visa?.textContent).toMatch(texto('Disponível: R$ 4.800,00 de R$ 5.000,00'))
      expect(within(visa as HTMLElement).getByRole('button', { name: 'Faturas de Visa' })).toBeVisible()
      expect(screen.queryByRole('button', { name: 'Faturas de Nubank' })).not.toBeInTheDocument()
    })
  })

  describe('faturas', () => {
    it('pede de 3 meses atrás a 6 à frente e mostra cada fatura com a situação', async () => {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta(), cartaoVisa()]),
        [FATURAS]: responde({
          contaId: 'c9',
          faturas: [
            fatura({ vencimento: '2026-10-10', fechamento: '2026-09-30', situacao: 'paga', pagoCentavos: 20000, restanteCentavos: 0 }),
            fatura(),
            fatura({ vencimento: '2026-12-10', fechamento: '2026-11-30', situacao: 'aberta', totalCentavos: 5000, restanteCentavos: 5000 }),
          ],
        }),
      })
      render(<App />)
      expect(await screen.findByRole('button', { name: 'Faturas de Visa' })).toHaveAttribute('aria-expanded', 'false')
      await abrirFaturas()
      expect(await screen.findByText('Vence em 10/11/2026')).toBeVisible()
      expect(chamadasA(FATURAS)).toHaveLength(1)
      const itens = within(screen.getByRole('heading', { name: 'Faturas de Visa' }).parentElement as HTMLElement).getAllByRole('listitem')
      expect(itens).toHaveLength(3)
      expect(itens[0]).toHaveTextContent('Situação: Paga')
      expect(itens[0].textContent).toMatch(texto('Pago: R$ 200,00'))
      expect(itens[0].textContent).not.toMatch(/Restante/)
      expect(itens[1]).toHaveTextContent('Fecha em 31/10/2026')
      expect(itens[1].textContent).toMatch(texto('Total: R$ 200,00'))
      // Pago zero não aparece; restante só quando há valor.
      expect(itens[1].textContent).not.toMatch(/Pago/)
      expect(itens[1].textContent).toMatch(texto('Restante: R$ 200,00'))
      expect(itens[1]).toHaveTextContent('Situação: Fechada')
      expect(itens[2]).toHaveTextContent('Situação: Aberta')
      // Só a fechada com restante tem o botão.
      expect(screen.getAllByRole('button', { name: /pagar fatura/i })).toHaveLength(1)
    })

    it('fatura sem compras vira linha esmaecida, a aberta é destacada e a fechada com restante traz o botão', async () => {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta(), cartaoVisa()]),
        [FATURAS]: responde({
          contaId: 'c9',
          faturas: [
            fatura({ vencimento: '2026-09-10', fechamento: '2026-08-31', situacao: 'paga', totalCentavos: 0, pagoCentavos: 0, restanteCentavos: 0 }),
            fatura(),
            fatura({ vencimento: '2026-12-10', fechamento: '2026-11-30', situacao: 'aberta', totalCentavos: 5000, restanteCentavos: 5000 }),
          ],
        }),
      })
      render(<App />)
      await abrirFaturas()
      const itens = within(
        (await screen.findByRole('heading', { name: 'Faturas de Visa' })).parentElement as HTMLElement,
      ).getAllByRole('listitem')
      expect(itens[0]).toHaveTextContent('sem compras')
      expect(itens[0].textContent).not.toMatch(/Total/)
      expect(itens[1]).toHaveClass('a-pagar')
      expect(within(itens[1]).getByRole('button', { name: 'Pagar fatura de 10/11/2026' })).toBeVisible()
      expect(itens[2]).toHaveClass('atual')
      expect(itens[0]).not.toHaveClass('atual')
    })

    it('mostra carregando, erro com nova tentativa e vazio', async () => {
      let chamadas = 0
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta(), cartaoVisa()]),
        [FATURAS]: () => {
          chamadas += 1
          if (chamadas === 1) return Promise.resolve(jsonResponse({ erro: 'x' }, 500))
          return Promise.resolve(jsonResponse({ contaId: 'c9', faturas: [] }, 200))
        },
      })
      render(<App />)
      await abrirFaturas()
      expect(await screen.findByText('Não foi possível carregar as faturas.')).toBeVisible()
      fireEvent.click(screen.getByRole('button', { name: 'Tentar de novo' }))
      expect(await screen.findByText('Nenhuma fatura no período.')).toBeVisible()
    })

    it('mostra carregando enquanto as faturas não chegam', async () => {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta(), cartaoVisa()]),
        [FATURAS]: () => new Promise<Response>(() => {}),
      })
      render(<App />)
      await abrirFaturas()
      expect(await screen.findByText('Carregando faturas...')).toBeVisible()
    })

    it('Pagar fatura preenche a transferência, envia e recarrega faturas e saldo', async () => {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta(), cartaoVisa()]),
        [FATURAS]: responde({ contaId: 'c9', faturas: [fatura()] }),
        'POST /transferencias': responde(
          {
            id: 't1',
            contaOrigemId: 'c1',
            contaDestinoId: 'c9',
            valorCentavos: 20000,
            data: '2026-11-10',
            titulo: 'Pagamento da fatura',
            descricao: null,
            saidaId: 'p1',
            entradaId: 'p2',
            criadaEm: '2026-10-19T12:00:00Z',
          },
          201,
        ),
      })
      render(<App />)
      await abrirFaturas()
      fireEvent.click(await screen.findByRole('button', { name: 'Pagar fatura de 10/11/2026' }))
      const form = await screen.findByRole('form', { name: 'Pagamento da fatura' })
      expect(within(form).getByLabelText('Conta de origem')).toHaveValue('c1')
      expect(within(form).getByLabelText('Conta de destino')).toHaveValue('c9')
      expect(within(form).getByLabelText('Valor em reais')).toHaveValue('200,00')
      expect(within(form).getByLabelText('Data do pagamento')).toHaveValue('2026-11-10')
      expect(within(form).getByLabelText('Título')).toHaveValue('Pagamento da fatura')
      expect(within(form).getByText(/depois do próximo fechamento conta na fatura seguinte/i)).toBeVisible()
      const contasAntes = chamadasA('GET /contas').length
      fireEvent.submit(form)
      await waitFor(() => expect(chamadasA('POST /transferencias')).toHaveLength(1))
      expect(JSON.parse(String(chamadasA('POST /transferencias')[0][1]?.body))).toEqual({
        contaOrigemId: 'c1',
        contaDestinoId: 'c9',
        valorCentavos: 20000,
        data: '2026-11-10',
        titulo: 'Pagamento da fatura',
      })
      await waitFor(() => expect(chamadasA(FATURAS)).toHaveLength(2))
      await waitFor(() => expect(chamadasA('GET /contas').length).toBeGreaterThan(contasAntes))
      await waitFor(() => expect(screen.queryByRole('form', { name: 'Pagamento da fatura' })).not.toBeInTheDocument())
    })

    it('erro do servidor no pagamento aparece e o formulário continua aberto', async () => {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta(), cartaoVisa()]),
        [FATURAS]: responde({ contaId: 'c9', faturas: [fatura()] }),
        'POST /transferencias': responde({ erro: 'saldo da conta fora do limite' }, 400),
      })
      render(<App />)
      await abrirFaturas()
      fireEvent.click(await screen.findByRole('button', { name: 'Pagar fatura de 10/11/2026' }))
      const form = await screen.findByRole('form', { name: 'Pagamento da fatura' })
      fireEvent.submit(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent('saldo da conta fora do limite')
    })

    it('conta de pagamento arquivada deixa escolher outra conta corrente ou poupança', async () => {
      mockFetch({
        ...sessaoViva,
        // c1 (conta de pagamento do cartão) não vem entre as ativas.
        'GET /contas': responde([
          conta({ id: 'c2', nome: 'Reserva', tipo: 'poupanca' }),
          conta({ id: 'c3', nome: 'Carteira', tipo: 'dinheiro' }),
          cartaoVisa(),
        ]),
        [FATURAS]: responde({ contaId: 'c9', faturas: [fatura()] }),
        'POST /transferencias': responde(
          {
            id: 't1',
            contaOrigemId: 'c2',
            contaDestinoId: 'c9',
            valorCentavos: 20000,
            data: '2026-11-10',
            titulo: 'Pagamento da fatura',
            descricao: null,
            saidaId: 'p1',
            entradaId: 'p2',
            criadaEm: '2026-10-19T12:00:00Z',
          },
          201,
        ),
      })
      render(<App />)
      await abrirFaturas()
      fireEvent.click(await screen.findByRole('button', { name: 'Pagar fatura de 10/11/2026' }))
      const form = await screen.findByRole('form', { name: 'Pagamento da fatura' })
      expect(within(form).getByText(/conta de pagamento deste cartão está arquivada/i)).toBeVisible()
      const origem = within(form).getByLabelText('Conta de origem')
      expect(within(origem).getAllByRole('option').map((o) => o.textContent)).toEqual(['Reserva'])
      fireEvent.submit(form)
      await waitFor(() => expect(chamadasA('POST /transferencias')).toHaveLength(1))
      expect(JSON.parse(String(chamadasA('POST /transferencias')[0][1]?.body))).toMatchObject({
        contaOrigemId: 'c2',
        contaDestinoId: 'c9',
      })
    })
  })

  describe('lançamento e recorrência em conta de cartão', () => {
    beforeEach(() => {
      window.location.hash = '#/lancamentos'
    })

    it('troca o rótulo da data e mostra a fatura em que a compra entra', async () => {
      mockFetch({ ...sessaoViva, 'GET /contas': responde([conta(), cartaoVisa()]) })
      render(<App />)
      const form = await abrirPainel('Novo', 'Novo lançamento')
      expect(within(form).getByLabelText('Data')).toBeVisible()
      expect(within(form).queryByText(/entra na fatura/i)).not.toBeInTheDocument()
      fireEvent.change(within(form).getByLabelText('Conta'), { target: { value: 'c9' } })
      expect(within(form).getByLabelText('Data da compra')).toBeVisible()
      // Compra em 19/10, fecha dia 31, vence dia 10: fatura de 10/11.
      expect(within(form).getByText('Entra na fatura que vence em 10/11')).toBeVisible()
      fireEvent.change(within(form).getByLabelText('Data da compra'), { target: { value: '2026-12-01' } })
      expect(within(form).getByText('Entra na fatura que vence em 10/01')).toBeVisible()
    })

    function compra(sobra: Partial<Record<string, unknown>>): Record<string, unknown> {
      return {
        id: 'l1',
        tipo: 'despesa',
        status: 'realizado',
        titulo: 'Feira',
        descricao: null,
        valorCentavos: 12345,
        categoriaId: 'cd1',
        transferenciaId: null,
        templateId: null,
        parcelaOrdem: null,
        compraParceladaId: null,
        parcelaNum: null,
        parcelaTotal: null,
        direcao: null,
        contaId: 'c9',
        dataCompetencia: '2026-10-03',
        dataCaixa: '2026-11-10',
        criadoEm: '2026-10-03T12:00:00Z',
        ...sobra,
      }
    }

    async function editarSemMexerNaData(lancamento: Record<string, unknown>): Promise<HTMLElement> {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta(), cartaoVisa()]),
        'GET /lancamentos?de=2026-10-01&ate=2026-10-31': responde([lancamento]),
        'PUT /lancamentos/l1': responde(lancamento),
      })
      render(<App />)
      fireEvent.click(await acao('Editar Feira'))
      const form = await screen.findByRole('form', { name: 'Editar lançamento' })
      fireEvent.submit(form)
      await waitFor(() => expect(chamadasA('PUT /lancamentos/l1')).toHaveLength(1))
      return form
    }

    it('editar compra de cartão abre com a data da compra e reenvia essa data', async () => {
      mockFetch({
        ...sessaoViva,
        'GET /contas': responde([conta(), cartaoVisa()]),
        'GET /lancamentos?de=2026-10-01&ate=2026-10-31': responde([compra({})]),
        'PUT /lancamentos/l1': responde(compra({})),
      })
      render(<App />)
      fireEvent.click(await acao('Editar Feira'))
      const form = await screen.findByRole('form', { name: 'Editar lançamento' })
      expect(within(form).getByLabelText('Data da compra')).toHaveValue('2026-10-03')
      fireEvent.submit(form)
      await waitFor(() => expect(chamadasA('PUT /lancamentos/l1')).toHaveLength(1))
      expect(JSON.parse(String(chamadasA('PUT /lancamentos/l1')[0][1]?.body))).toMatchObject({
        data: '2026-10-03',
        contaId: 'c9',
      })
    })

    it('editar lançamento fora de cartão continua com a mesma data', async () => {
      await editarSemMexerNaData(
        compra({ contaId: 'c1', dataCompetencia: '2026-10-05', dataCaixa: '2026-10-05' }),
      )
      expect(JSON.parse(String(chamadasA('PUT /lancamentos/l1')[0][1]?.body))).toMatchObject({
        data: '2026-10-05',
        contaId: 'c1',
      })
    })

    describe('confirmar uma prevista', () => {
      function prevista(sobra: Partial<Record<string, unknown>>): Record<string, unknown> {
        return {
          id: 'o1',
          tipo: 'despesa',
          status: 'previsto',
          titulo: 'Assinatura',
          descricao: null,
          valorCentavos: 5000,
          categoriaId: 'cd1',
          transferenciaId: null,
          templateId: 'r1',
          parcelaOrdem: null,
          compraParceladaId: null,
          parcelaNum: null,
          parcelaTotal: null,
          direcao: null,
          contaId: 'c9',
          dataCompetencia: '2026-10-03',
          dataCaixa: '2026-11-10',
          criadoEm: '2026-10-01T12:00:00Z',
          ...sobra,
        }
      }

      async function abrir(lancamento: Record<string, unknown>): Promise<HTMLElement> {
        mockFetch({
          ...sessaoViva,
          'GET /contas': responde([conta(), cartaoVisa()]),
          'GET /lancamentos?de=2026-10-01&ate=2026-10-31': responde([lancamento]),
          'PATCH /lancamentos/o1': responde({ ...lancamento, status: 'realizado' }),
        })
        render(<App />)
        fireEvent.click(await screen.findByRole('button', { name: 'Confirmar Assinatura' }))
        return screen.findByRole('form', { name: 'Confirmar Assinatura' })
      }

      const corpoDoPatch = (): unknown => JSON.parse(String(chamadasA('PATCH /lancamentos/o1')[0][1]?.body))

      it('em cartão abre com a data da compra, mostra a fatura e sem mudar manda só o status', async () => {
        const form = await abrir(prevista({}))
        expect(within(form).getByLabelText('Data da compra')).toHaveValue('2026-10-03')
        expect(within(form).getByText('Entra na fatura que vence em 10/11')).toBeVisible()
        fireEvent.click(within(form).getByRole('button', { name: 'Confirmar' }))
        await waitFor(() => expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(1))
        expect(corpoDoPatch()).toEqual({ status: 'realizado' })
      })

      it('em cartão, mudar a data manda a nova data da compra', async () => {
        const form = await abrir(prevista({}))
        fireEvent.change(within(form).getByLabelText('Data da compra'), { target: { value: '2026-10-05' } })
        fireEvent.click(within(form).getByRole('button', { name: 'Confirmar' }))
        await waitFor(() => expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(1))
        expect(corpoDoPatch()).toEqual({ status: 'realizado', data: '2026-10-05' })
      })

      it('fora de cartão segue com a data de sempre', async () => {
        const form = await abrir(prevista({ contaId: 'c1', dataCompetencia: '2026-10-25', dataCaixa: '2026-10-25' }))
        expect(within(form).getByLabelText('Data')).toHaveValue('2026-10-25')
        expect(within(form).queryByText(/entra na fatura/i)).not.toBeInTheDocument()
        fireEvent.click(within(form).getByRole('button', { name: 'Confirmar' }))
        await waitFor(() => expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(1))
        expect(corpoDoPatch()).toEqual({ status: 'realizado' })
      })
    })

    describe('cartão não é origem de transferência', () => {
      async function abrirTransferencia(contas: Record<string, unknown>[]): Promise<void> {
        mockFetch({ ...sessaoViva, 'GET /contas': responde(contas) })
        render(<App />)
        await abrirPainel('Novo', 'Novo lançamento')
        fireEvent.click(await screen.findByRole('button', { name: 'Transferência' }))
      }

      it('o select de origem não lista o cartão e o de destino lista', async () => {
        await abrirTransferencia([conta(), conta({ id: 'c2', nome: 'Reserva', tipo: 'poupanca' }), cartaoVisa()])
        const form = await screen.findByRole('form', { name: 'Nova transferência' })
        const nomes = (rotulo: string): (string | null)[] =>
          within(within(form).getByLabelText(rotulo)).getAllByRole('option').map((o) => o.textContent)
        expect(nomes('Conta de origem')).toEqual(['Nubank', 'Reserva'])
        expect(nomes('Conta de destino')).toEqual(['Nubank', 'Reserva', 'Visa'])
      })

      it('uma conta comum mais um cartão ainda deixa transferir, com o cartão só no destino', async () => {
        await abrirTransferencia([conta(), cartaoVisa()])
        const form = await screen.findByRole('form', { name: 'Nova transferência' })
        expect(within(form).getByLabelText('Conta de origem')).toHaveValue('c1')
        expect(within(form).getByLabelText('Conta de destino')).toHaveValue('c9')
      })

      it('só cartões, ou uma conta sozinha, mostram o aviso de duas contas', async () => {
        await abrirTransferencia([cartaoVisa(), cartaoVisa({ id: 'c8', nome: 'Master' })])
        expect(await screen.findByText('Crie duas contas para transferir.')).toBeVisible()
        expect(screen.queryByRole('form', { name: 'Nova transferência' })).toBeNull()
      })

      it('o Pagar fatura também não oferece cartão como origem', async () => {
        mockFetch({
          ...sessaoViva,
          'GET /contas': responde([conta(), cartaoVisa(), cartaoVisa({ id: 'c8', nome: 'Master' })]),
          [FATURAS]: responde({ contaId: 'c9', faturas: [fatura()] }),
        })
        window.location.hash = '#/contas'
        render(<App />)
        await abrirFaturas()
        fireEvent.click(await screen.findByRole('button', { name: 'Pagar fatura de 10/11/2026' }))
        const form = await screen.findByRole('form', { name: 'Pagamento da fatura' })
        const origem = within(form).getByLabelText('Conta de origem')
        expect(within(origem).getAllByRole('option').map((o) => o.textContent)).toEqual(['Nubank'])
      })
    })

    describe('benefício não participa de transferência nem de pagamento de fatura', () => {
      const vr = conta({ id: 'b1', nome: 'VR', tipo: 'beneficio', categoriasPermitidas: [] })

      it('o Pagar fatura não lista benefício em origem nem destino', async () => {
        mockFetch({
          ...sessaoViva,
          'GET /contas': responde([conta(), vr, cartaoVisa()]),
          [FATURAS]: responde({ contaId: 'c9', faturas: [fatura()] }),
        })
        window.location.hash = '#/contas'
        render(<App />)
        await abrirFaturas()
        fireEvent.click(await screen.findByRole('button', { name: 'Pagar fatura de 10/11/2026' }))
        const form = await screen.findByRole('form', { name: 'Pagamento da fatura' })
        const nomes = (rotulo: string): (string | null)[] =>
          within(within(form).getByLabelText(rotulo)).getAllByRole('option').map((o) => o.textContent)
        expect(nomes('Conta de origem')).toEqual(['Nubank'])
        expect(nomes('Conta de destino')).toEqual(['Nubank', 'Visa'])
      })
    })

    it('recorrência em cartão avisa que as ocorrências entram na fatura de cada mês', async () => {
      mockFetch({ ...sessaoViva, 'GET /contas': responde([conta(), cartaoVisa()]) })
      window.location.hash = '#/recorrencias'
      render(<App />)
      const form = await abrirPainel('Nova recorrência', 'Nova recorrência')
      expect(within(form).queryByText(/entram na fatura de cada mês/i)).not.toBeInTheDocument()
      fireEvent.change(within(form).getByLabelText('Conta'), { target: { value: 'c9' } })
      expect(within(form).getByText('As ocorrências entram na fatura de cada mês.')).toBeVisible()
    })
  })
})
