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
    ...sobra,
  }
}

function categoria(sobra: Partial<Record<string, unknown>>): Record<string, unknown> {
  return { paiId: null, cor: null, icone: null, ...sobra }
}

const categorias = [
  categoria({ id: 'cd1', nome: 'Mercado', tipo: 'despesa' }),
  categoria({ id: 'cd2', nome: 'Transporte', tipo: 'despesa' }),
  categoria({ id: 'cr1', nome: 'Salário', tipo: 'receita' }),
]

function lancamento(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
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
    direcao: null,
    contaId: 'c1',
    dataCompetencia: '2026-10-05',
    dataCaixa: '2026-10-05',
    criadoEm: '2026-10-05T12:00:00Z',
    ...sobra,
  }
}

function prevista(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return lancamento({
    id: 'o1',
    status: 'previsto',
    titulo: 'Aluguel',
    valorCentavos: 150000,
    templateId: 'r1',
    dataCompetencia: '2026-10-25',
    dataCaixa: '2026-10-25',
    ...sobra,
  })
}

const recorrenciaAtiva = {
  id: 'r1',
  tipo: 'despesa',
  titulo: 'Aluguel',
  descricao: null,
  valorCentavos: 150000,
  categoriaId: 'cd1',
  contaId: 'c1',
  ativo: true,
  criadoEm: '2026-10-01T12:00:00Z',
  regra: { frequencia: 'mensal', intervalo: 1, diaDoMes: 25, diaDaSemana: null, inicio: '2026-10-25', fim: null, ocorrenciasMax: null },
  divisao: null,
  proximaOcorrencia: '2026-10-25',
}

function perna(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return lancamento({
    id: 'p1',
    tipo: 'transferencia',
    titulo: 'Reserva',
    valorCentavos: 50000,
    categoriaId: null,
    transferenciaId: 't1',
    direcao: 'saida',
    ...sobra,
  })
}

const contaB = conta({ id: 'c2', nome: 'Carteira', saldoCentavos: 0 })

const transferenciaCriada = {
  id: 't1',
  contaOrigemId: 'c1',
  contaDestinoId: 'c2',
  valorCentavos: 50000,
  data: '2026-10-19',
  titulo: 'Transferência',
  descricao: null,
  saidaId: 'p1',
  entradaId: 'p2',
  criadaEm: '2026-10-19T12:00:00Z',
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

const responde = (corpo: unknown, status = 200) => () => Promise.resolve(jsonResponse(corpo, status))

const MES_ATUAL = 'GET /lancamentos?de=2026-10-01&ate=2026-10-31'

function mockFetch(rotas: Rotas): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const rota = rotas[chave]
      if (rota) return rota(init?.body)
      if (chave === 'GET /healthz') return Promise.resolve(jsonResponse({ mensagem: 'ok' }, 200))
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

const base = {
  'GET /auth/eu': responde(usuario),
  'GET /contas': responde([conta()]),
  'GET /categorias': responde(categorias),
  'GET /recorrencias': responde([]),
  'GET /lancamentos/vencidos': responde({ lancamentos: [], maisAlgum: false }),
}

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp =>
  new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replaceAll(' ', '[\\s\\u00a0]'))

const secao = async (): Promise<HTMLElement> => screen.findByRole('region', { name: 'Lançamentos' })

async function formulario(nome = 'Novo lançamento'): Promise<HTMLElement> {
  return screen.findByRole('form', { name: nome })
}

function preencher(form: HTMLElement, titulo: string, valor: string): void {
  fireEvent.change(within(form).getByLabelText('Título'), { target: { value: titulo } })
  fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: valor } })
}

// Confirmar abre o mini formulário no item; este passo manda o formulário aberto.
async function abrirConfirmacao(titulo: string): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: `Confirmar ${titulo}` }))
  return screen.findByRole('form', { name: `Confirmar ${titulo}` })
}

function enviarConfirmacao(form: HTMLElement): void {
  fireEvent.click(within(form).getByRole('button', { name: 'Confirmar' }))
}

describe('Lancamentos', () => {
  beforeEach(() => {
    // Só o Date: com os timers falsos o waitFor do testing-library travaria.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 9, 19, 23, 30))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('mostra carregando enquanto a lista não chega', async () => {
    mockFetch({ ...base, [MES_ATUAL]: () => new Promise<Response>(() => {}) })
    render(<App />)
    expect(await screen.findByText(/carregando lançamentos/i)).toBeVisible()
  })

  it('lista com data, categoria, conta, sinal e totais do mês', async () => {
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([
        lancamento(),
        lancamento({
          id: 'l2',
          tipo: 'receita',
          titulo: 'Pagamento',
          valorCentavos: 500000,
          categoriaId: 'cr1',
          dataCaixa: '2026-10-01',
        }),
      ]),
    })
    render(<App />)
    const area = within(await secao())
    const itens = await area.findAllByRole('listitem')
    expect(itens).toHaveLength(2)
    expect(itens[0]).toHaveTextContent('05/10')
    expect(itens[0]).toHaveTextContent('Feira')
    expect(itens[0]).toHaveTextContent('Mercado · Nubank')
    expect(itens[0]).toHaveTextContent(texto('− R$ 123,45'))
    expect(itens[1]).toHaveTextContent(texto('+ R$ 5.000,00'))
    expect(itens[1]).toHaveTextContent('Salário')
    expect(area.getByText('Receitas').nextSibling).toHaveTextContent(texto('R$ 5.000,00'))
    expect(area.getByText('Despesas').nextSibling).toHaveTextContent(texto('R$ 123,45'))
    expect(area.getByText('Resultado').nextSibling).toHaveTextContent(texto('R$ 4.876,55'))
  })

  it('mostra o mês corrente pela data local, mesmo às 23h30', async () => {
    mockFetch({ ...base, [MES_ATUAL]: responde([]) })
    render(<App />)
    expect(await screen.findByText('outubro de 2026')).toBeVisible()
    expect(await screen.findByText('Nenhum lançamento neste mês.')).toBeVisible()
  })

  it('erro mostra alerta e "Tentar de novo" busca outra vez', async () => {
    mockFetch({ ...base, [MES_ATUAL]: responde({ erro: 'x' }, 500) })
    render(<App />)
    const area = within(await secao())
    expect(await area.findByText('Não foi possível carregar os lançamentos.')).toBeVisible()
    fireEvent.click(area.getByRole('button', { name: /tentar de novo/i }))
    await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
  })

  it('503 mostra serviço indisponível', async () => {
    mockFetch({ ...base, [MES_ATUAL]: responde({ erro: 'x' }, 503) })
    render(<App />)
    expect(await within(await secao()).findByText(/serviço indisponível/i)).toBeVisible()
  })

  it('401 na lista volta para o login', async () => {
    mockFetch({ ...base, [MES_ATUAL]: responde({ erro: 'x' }, 401) })
    render(<App />)
    expect(await screen.findByRole('status')).toHaveTextContent(/sessão acabou/i)
  })

  it('trocar de mês pede o período certo', async () => {
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([]),
      'GET /lancamentos?de=2026-09-01&ate=2026-09-30': responde([]),
      'GET /lancamentos?de=2026-10-01&ate=2026-10-31&contaId=c1': responde([]),
    })
    render(<App />)
    const area = within(await secao())
    fireEvent.click(await area.findByRole('button', { name: /mês anterior/i }))
    expect(await area.findByText('setembro de 2026')).toBeVisible()
    await waitFor(() => expect(chamadasA('GET /lancamentos?de=2026-09-01&ate=2026-09-30')).toHaveLength(1))
  })

  it('filtrar por conta manda contaId', async () => {
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([]),
      'GET /lancamentos?de=2026-10-01&ate=2026-10-31&contaId=c1': responde([]),
    })
    render(<App />)
    const area = within(await secao())
    fireEvent.change(await area.findByLabelText('Filtrar por conta'), { target: { value: 'c1' } })
    await waitFor(() =>
      expect(chamadasA('GET /lancamentos?de=2026-10-01&ate=2026-10-31&contaId=c1')).toHaveLength(1),
    )
  })

  it('cria com valor em centavos e data local, e recarrega lista e saldo', async () => {
    let corpoEnviado: unknown
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([]),
      'POST /lancamentos': (corpo) => {
        corpoEnviado = corpo
        return Promise.resolve(jsonResponse(lancamento(), 201))
      },
    })
    render(<App />)
    const form = await formulario()
    preencher(form, '  Feira  ', '1.234,56')
    fireEvent.change(within(form).getByLabelText(/descrição/i), { target: { value: 'semanal' } })
    fireEvent.submit(form)
    await waitFor(() => expect(chamadasA('POST /lancamentos')).toHaveLength(1))
    expect(JSON.parse(String(corpoEnviado))).toEqual({
      tipo: 'despesa',
      titulo: 'Feira',
      descricao: 'semanal',
      valorCentavos: 123456,
      categoriaId: 'cd1',
      contaId: 'c1',
      data: '2026-10-19',
    })
    await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
    await waitFor(() => expect(chamadasA('GET /contas')).toHaveLength(2))
    // O formulário remonta depois de salvar, então a busca é de novo.
    await waitFor(async () => expect(within(await formulario()).getByLabelText('Título')).toHaveValue(''))
  })

  it('trocar o tipo troca as categorias', async () => {
    mockFetch({ ...base, [MES_ATUAL]: responde([]) })
    render(<App />)
    const form = await formulario()
    const select = within(form).getByLabelText('Categoria')
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Mercado', 'Transporte'])
    fireEvent.change(within(form).getByLabelText('Tipo'), { target: { value: 'receita' } })
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Salário'])
  })

  it.each([['0'], ['0,00'], ['-5,00'], ['abc'], ['1,234']])('valor inválido "%s" não envia', async (valor) => {
    mockFetch({ ...base, [MES_ATUAL]: responde([]) })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Feira', valor)
    fireEvent.submit(form)
    expect(await within(form).findByRole('alert')).toHaveTextContent(/valor inválido/i)
    expect(chamadasA('POST /lancamentos')).toHaveLength(0)
  })

  it('400 mostra a mensagem do servidor e mantém o que foi digitado', async () => {
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([]),
      'POST /lancamentos': responde({ erro: 'conta arquivada' }, 400),
    })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Feira', '10,00')
    fireEvent.submit(form)
    expect(await within(form).findByRole('alert')).toHaveTextContent('conta arquivada')
    expect(within(form).getByLabelText('Título')).toHaveValue('Feira')
  })

  it('401 ao criar volta para o login', async () => {
    mockFetch({ ...base, [MES_ATUAL]: responde([]), 'POST /lancamentos': responde({ erro: 'x' }, 401) })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Feira', '10,00')
    fireEvent.submit(form)
    expect(await screen.findByRole('status')).toHaveTextContent(/sessão acabou/i)
  })

  it('submit duplo manda uma só requisição', async () => {
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([]),
      'POST /lancamentos': () => new Promise<Response>(() => {}),
    })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Feira', '10,00')
    fireEvent.submit(form)
    fireEvent.submit(form)
    expect(chamadasA('POST /lancamentos')).toHaveLength(1)
    expect(within(form).getByRole('button', { name: /salvando/i })).toBeDisabled()
  })

  it('edita com PUT no id certo e recarrega', async () => {
    let corpoEnviado: unknown
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([lancamento()]),
      'PUT /lancamentos/l1': (corpo) => {
        corpoEnviado = corpo
        return Promise.resolve(jsonResponse(lancamento({ titulo: 'Feira grande' }), 200))
      },
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Editar Feira' }))
    const form = await formulario('Editar lançamento')
    expect(within(form).getByLabelText(/valor em reais/i)).toHaveValue('123,45')
    expect(within(form).getByLabelText('Data')).toHaveValue('2026-10-05')
    fireEvent.change(within(form).getByLabelText('Título'), { target: { value: 'Feira grande' } })
    fireEvent.submit(form)
    await waitFor(() => expect(chamadasA('PUT /lancamentos/l1')).toHaveLength(1))
    expect(JSON.parse(String(corpoEnviado))).toMatchObject({
      titulo: 'Feira grande',
      valorCentavos: 12345,
      data: '2026-10-05',
    })
    await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
    expect(await formulario()).toBeVisible()
  })

  it('apagar pede confirmação, envia DELETE e recarrega lista e saldo', async () => {
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([lancamento()]),
      'DELETE /lancamentos/l1': () => Promise.resolve(new Response(null, { status: 204 })),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Apagar Feira' }))
    expect(chamadasA('DELETE /lancamentos/l1')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar apagar Feira' }))
    await waitFor(() => expect(chamadasA('DELETE /lancamentos/l1')).toHaveLength(1))
    await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
    await waitFor(() => expect(chamadasA('GET /contas')).toHaveLength(2))
  })

  it('400 ao apagar mostra a mensagem do servidor e mantém o item', async () => {
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([lancamento()]),
      'DELETE /lancamentos/l1': responde({ erro: 'saldo da conta fora do limite' }, 400),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Apagar Feira' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar apagar Feira' }))
    expect(await screen.findByText('saldo da conta fora do limite')).toBeVisible()
    expect(screen.getByRole('button', { name: 'Apagar Feira' })).toBeVisible()
  })

  it('cancelar a confirmação não apaga', async () => {
    mockFetch({ ...base, [MES_ATUAL]: responde([lancamento()]) })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Apagar Feira' }))
    const area = within(await secao())
    fireEvent.click(area.getByRole('button', { name: 'Cancelar' }))
    expect(area.getByRole('button', { name: 'Apagar Feira' })).toBeVisible()
    expect(chamadasA('DELETE /lancamentos/l1')).toHaveLength(0)
  })

  it('404 ao apagar avisa que não existe mais e recarrega', async () => {
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([lancamento()]),
      'DELETE /lancamentos/l1': responde({ erro: 'x' }, 404),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Apagar Feira' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar apagar Feira' }))
    expect(await screen.findByText('Esse lançamento não existe mais.')).toBeVisible()
    await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
  })

  it('404 ao editar avisa que não existe mais e recarrega', async () => {
    mockFetch({
      ...base,
      [MES_ATUAL]: responde([lancamento()]),
      'PUT /lancamentos/l1': responde({ erro: 'x' }, 404),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Editar Feira' }))
    fireEvent.submit(await formulario('Editar lançamento'))
    expect(await screen.findByText('Esse lançamento não existe mais.')).toBeVisible()
    await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
  })

  it('sem conta ativa o formulário pede para criar uma conta', async () => {
    mockFetch({ ...base, 'GET /contas': responde([]), [MES_ATUAL]: responde([]) })
    render(<App />)
    expect(await within(await secao()).findByText('Crie uma conta antes de lançar.')).toBeVisible()
    expect(screen.queryByRole('form', { name: 'Novo lançamento' })).toBeNull()
  })

  it('desarquivar na visão de arquivadas põe a conta no select do formulário', async () => {
    let ativas = [conta()]
    mockFetch({
      ...base,
      'GET /contas': () => Promise.resolve(jsonResponse(ativas, 200)),
      'GET /contas?arquivadas=true': responde([conta({ id: 'c2', nome: 'Reserva', arquivada: true })]),
      'PATCH /contas/c2': () => {
        ativas = [conta(), conta({ id: 'c2', nome: 'Reserva' })]
        return Promise.resolve(jsonResponse(conta({ id: 'c2', nome: 'Reserva' }), 200))
      },
      [MES_ATUAL]: responde([]),
    })
    render(<App />)
    const form = await formulario()
    expect(within(within(form).getByLabelText('Conta')).queryByText('Reserva')).toBeNull()
    fireEvent.click(await screen.findByRole('button', { name: /ver arquivadas/i }))
    fireEvent.click(await screen.findByRole('button', { name: 'Desarquivar Reserva' }))
    await waitFor(async () =>
      expect(within(within(await formulario()).getByLabelText('Conta')).getByText('Reserva')).toBeVisible(),
    )
  })
  describe('transferência', () => {
    const duasContas = { ...base, 'GET /contas': responde([conta(), contaB]) }

    async function abrirTransferencia(): Promise<HTMLElement> {
      fireEvent.click(await screen.findByRole('button', { name: 'Transferência' }))
      return formulario('Nova transferência')
    }

    it('mostra a perna com sinal pela direção e fora dos totais', async () => {
      mockFetch({
        ...duasContas,
        [MES_ATUAL]: responde([
          lancamento(),
          perna(),
          perna({ id: 'p2', contaId: 'c2', direcao: 'entrada', titulo: 'Reserva entrada' }),
        ]),
      })
      render(<App />)
      const area = within(await secao())
      const itens = await area.findAllByRole('listitem')
      expect(itens).toHaveLength(3)
      expect(itens[1]).toHaveTextContent('Transferência · Nubank')
      expect(itens[1]).toHaveTextContent(texto('− R$ 500,00'))
      expect(itens[2]).toHaveTextContent('Transferência · Carteira')
      expect(itens[2]).toHaveTextContent(texto('+ R$ 500,00'))
      expect(area.getByText('Receitas').nextSibling).toHaveTextContent(texto('R$ 0,00'))
      expect(area.getByText('Despesas').nextSibling).toHaveTextContent(texto('R$ 123,45'))
      expect(area.getByText('Resultado').nextSibling).toHaveTextContent(texto('-R$ 123,45'))
    })

    it('perna não tem Editar', async () => {
      mockFetch({ ...duasContas, [MES_ATUAL]: responde([perna()]) })
      render(<App />)
      expect(await screen.findByRole('button', { name: 'Apagar Reserva' })).toBeVisible()
      expect(screen.queryByRole('button', { name: 'Editar Reserva' })).toBeNull()
    })

    it('apagar a perna avisa das duas pernas, chama DELETE /transferencias e recarrega', async () => {
      mockFetch({
        ...duasContas,
        [MES_ATUAL]: responde([perna()]),
        'DELETE /transferencias/t1': () => Promise.resolve(new Response(null, { status: 204 })),
      })
      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: 'Apagar Reserva' }))
      expect(screen.getByText(/as duas pernas/i)).toBeVisible()
      expect(chamadasA('DELETE /transferencias/t1')).toHaveLength(0)
      fireEvent.click(screen.getByRole('button', { name: 'Confirmar apagar Reserva' }))
      await waitFor(() => expect(chamadasA('DELETE /transferencias/t1')).toHaveLength(1))
      expect(chamadasA('DELETE /lancamentos/p1')).toHaveLength(0)
      await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
      await waitFor(() => expect(chamadasA('GET /contas')).toHaveLength(2))
    })

    it('cria enviando o corpo certo e recarrega lista e saldo', async () => {
      let corpoEnviado: unknown
      mockFetch({
        ...duasContas,
        [MES_ATUAL]: responde([]),
        'POST /transferencias': (corpo) => {
          corpoEnviado = corpo
          return Promise.resolve(jsonResponse(transferenciaCriada, 201))
        },
      })
      render(<App />)
      const form = await abrirTransferencia()
      fireEvent.change(within(form).getByLabelText('Conta de destino'), { target: { value: 'c2' } })
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: '500,00' } })
      fireEvent.submit(form)
      await waitFor(() => expect(chamadasA('POST /transferencias')).toHaveLength(1))
      expect(JSON.parse(String(corpoEnviado))).toEqual({
        contaOrigemId: 'c1',
        contaDestinoId: 'c2',
        valorCentavos: 50000,
        data: '2026-10-19',
      })
      await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
      await waitFor(() => expect(chamadasA('GET /contas')).toHaveLength(2))
    })

    it('envia título e descrição quando preenchidos', async () => {
      let corpoEnviado: unknown
      mockFetch({
        ...duasContas,
        [MES_ATUAL]: responde([]),
        'POST /transferencias': (corpo) => {
          corpoEnviado = corpo
          return Promise.resolve(jsonResponse(transferenciaCriada, 201))
        },
      })
      render(<App />)
      const form = await abrirTransferencia()
      fireEvent.change(within(form).getByLabelText(/título/i), { target: { value: '  Reserva  ' } })
      fireEvent.change(within(form).getByLabelText(/descrição/i), { target: { value: 'mês' } })
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: '1,00' } })
      fireEvent.submit(form)
      await waitFor(() => expect(chamadasA('POST /transferencias')).toHaveLength(1))
      expect(JSON.parse(String(corpoEnviado))).toMatchObject({ titulo: 'Reserva', descricao: 'mês' })
    })

    it('mesma conta em origem e destino não envia', async () => {
      mockFetch({ ...duasContas, [MES_ATUAL]: responde([]), 'POST /transferencias': responde(transferenciaCriada, 201) })
      render(<App />)
      const form = await abrirTransferencia()
      fireEvent.change(within(form).getByLabelText('Conta de destino'), { target: { value: 'c1' } })
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: '5,00' } })
      fireEvent.submit(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent(/contas diferentes/i)
      expect(chamadasA('POST /transferencias')).toHaveLength(0)
    })

    it('valor inválido não envia', async () => {
      mockFetch({ ...duasContas, [MES_ATUAL]: responde([]) })
      render(<App />)
      const form = await abrirTransferencia()
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: '0' } })
      fireEvent.submit(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent(/valor inválido/i)
      expect(chamadasA('POST /transferencias')).toHaveLength(0)
    })

    it('com menos de duas contas mostra o aviso', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([]) })
      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: 'Transferência' }))
      expect(await screen.findByText('Crie duas contas para transferir.')).toBeVisible()
      expect(screen.queryByRole('form', { name: 'Nova transferência' })).toBeNull()
    })

    it('400 mostra a mensagem do servidor e mantém o que foi digitado', async () => {
      mockFetch({
        ...duasContas,
        [MES_ATUAL]: responde([]),
        'POST /transferencias': responde({ erro: 'saldo da conta fora do limite' }, 400),
      })
      render(<App />)
      const form = await abrirTransferencia()
      fireEvent.change(within(form).getByLabelText('Conta de destino'), { target: { value: 'c2' } })
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: '5,00' } })
      fireEvent.submit(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent('saldo da conta fora do limite')
      expect(within(form).getByLabelText(/valor em reais/i)).toHaveValue('5,00')
      expect(within(form).getByRole('button', { name: /adicionar transferência/i })).toBeEnabled()
    })

    it('401 ao criar volta para o login', async () => {
      mockFetch({
        ...duasContas,
        [MES_ATUAL]: responde([]),
        'POST /transferencias': responde({ erro: 'x' }, 401),
      })
      render(<App />)
      const form = await abrirTransferencia()
      fireEvent.change(within(form).getByLabelText('Conta de destino'), { target: { value: 'c2' } })
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: '5,00' } })
      fireEvent.submit(form)
      expect(await screen.findByRole('form', { name: /entrar/i })).toBeVisible()
    })

    it('submit duplo manda uma só requisição', async () => {
      mockFetch({
        ...duasContas,
        [MES_ATUAL]: responde([]),
        'POST /transferencias': () => new Promise<Response>(() => {}),
      })
      render(<App />)
      const form = await abrirTransferencia()
      fireEvent.change(within(form).getByLabelText('Conta de destino'), { target: { value: 'c2' } })
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: '5,00' } })
      fireEvent.submit(form)
      fireEvent.submit(form)
      expect(chamadasA('POST /transferencias')).toHaveLength(1)
      expect(within(form).getByRole('button', { name: /salvando/i })).toBeDisabled()
    })
  })
  describe('ocorrências de recorrência', () => {
    it('prevista tem a marca Previsto, fica fora dos totais e entra na linha de previsto', async () => {
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([
          lancamento(),
          prevista(),
          prevista({ id: 'o2', tipo: 'receita', titulo: 'Bônus', categoriaId: 'cr1', valorCentavos: 20000 }),
        ]),
      })
      render(<App />)
      const area = within(await secao())
      const item = (await area.findByText('Aluguel')).closest('li')
      expect(item).toHaveTextContent('Previsto')
      expect(item).toHaveTextContent(texto('− R$ 1.500,00'))
      expect(area.getByText('Receitas').nextSibling).toHaveTextContent(texto('R$ 0,00'))
      expect(area.getByText('Despesas').nextSibling).toHaveTextContent(texto('R$ 123,45'))
      expect(area.getByText('Resultado').nextSibling).toHaveTextContent(texto('-R$ 123,45'))
      expect(area.getByText(/previsto no mês/i)).toHaveTextContent(texto('+R$ 200,00 / −R$ 1.500,00'))
    })

    it('cancelada aparece riscada com a marca Cancelado e fora dos totais', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista({ status: 'cancelado' })]) })
      render(<App />)
      const area = within(await secao())
      const item = (await area.findByText('Aluguel')).closest('li')
      expect(item).toHaveTextContent('Cancelado')
      expect(item).toHaveClass('cancelado')
      expect(area.getByText('Despesas').nextSibling).toHaveTextContent(texto('R$ 0,00'))
      expect(area.getByText(/previsto no mês/i)).toHaveTextContent(texto('+R$ 0,00 / −R$ 0,00'))
    })

    it('prevista não tem Apagar', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista()]) })
      render(<App />)
      await screen.findByRole('button', { name: 'Confirmar Aluguel' })
      expect(screen.queryByRole('button', { name: /apagar/i })).toBeNull()
    })

    it('confirmar envia PATCH realizado e recarrega lista e saldo', async () => {
      let corpoEnviado: unknown
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([prevista()]),
        'PATCH /lancamentos/o1': (corpo) => {
          corpoEnviado = corpo
          return Promise.resolve(jsonResponse(prevista({ status: 'realizado' }), 200))
        },
      })
      render(<App />)
      enviarConfirmacao(await abrirConfirmacao('Aluguel'))
      await waitFor(() => expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(1))
      expect(JSON.parse(String(corpoEnviado))).toEqual({ status: 'realizado' })
      await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
      await waitFor(() => expect(chamadasA('GET /contas')).toHaveLength(2))
    })

    it('cancelar pede confirmação, envia PATCH cancelado e recarrega', async () => {
      let corpoEnviado: unknown
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([prevista()]),
        'PATCH /lancamentos/o1': (corpo) => {
          corpoEnviado = corpo
          return Promise.resolve(jsonResponse(prevista({ status: 'cancelado' }), 200))
        },
      })
      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: 'Cancelar Aluguel' }))
      expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(0)
      fireEvent.click(screen.getByRole('button', { name: 'Confirmar cancelar Aluguel' }))
      await waitFor(() => expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(1))
      expect(JSON.parse(String(corpoEnviado))).toEqual({ status: 'cancelado' })
      await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
      await waitFor(() => expect(chamadasA('GET /contas')).toHaveLength(2))
    })

    it('desistir do cancelamento não envia nada', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista()]) })
      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: 'Cancelar Aluguel' }))
      fireEvent.click(screen.getByRole('button', { name: 'Manter' }))
      expect(screen.getByRole('button', { name: 'Cancelar Aluguel' })).toBeVisible()
      expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(0)
    })

    it('400 ao confirmar mostra a mensagem do servidor e mantém a prevista', async () => {
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([prevista()]),
        'PATCH /lancamentos/o1': responde({ erro: 'conta arquivada' }, 400),
      })
      render(<App />)
      const form = await abrirConfirmacao('Aluguel')
      enviarConfirmacao(form)
      expect(await screen.findByText('conta arquivada')).toBeVisible()
      // O formulário segue aberto para corrigir valor ou data.
      expect(within(form).getByRole('button', { name: 'Confirmar' })).toBeEnabled()
      expect(within(form).getByLabelText(/valor em reais/i)).toHaveValue('1.500,00')
    })

    it('503 ao confirmar mostra serviço indisponível', async () => {
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([prevista()]),
        'PATCH /lancamentos/o1': responde({ erro: 'x' }, 503),
      })
      render(<App />)
      enviarConfirmacao(await abrirConfirmacao('Aluguel'))
      expect(await screen.findByText('Serviço indisponível. Tente de novo em instantes.')).toBeVisible()
    })

    it('404 ao confirmar avisa que não existe mais e recarrega', async () => {
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([prevista()]),
        'PATCH /lancamentos/o1': responde({ erro: 'x' }, 404),
      })
      render(<App />)
      enviarConfirmacao(await abrirConfirmacao('Aluguel'))
      expect(await screen.findByText('Esse lançamento não existe mais.')).toBeVisible()
      await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
    })

    it('401 ao confirmar volta para o login', async () => {
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([prevista()]),
        'PATCH /lancamentos/o1': responde({ erro: 'x' }, 401),
      })
      render(<App />)
      enviarConfirmacao(await abrirConfirmacao('Aluguel'))
      expect(await screen.findByRole('status')).toHaveTextContent(/sessão acabou/i)
    })

    it('editar prevista envia PUT no id certo', async () => {
      let corpoEnviado: unknown
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([prevista()]),
        'PUT /lancamentos/o1': (corpo) => {
          corpoEnviado = corpo
          return Promise.resolve(jsonResponse(prevista({ titulo: 'Aluguel novo' }), 200))
        },
      })
      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: 'Editar Aluguel' }))
      const form = await formulario('Editar lançamento')
      expect(within(form).getByLabelText('Data')).toHaveValue('2026-10-25')
      fireEvent.change(within(form).getByLabelText('Título'), { target: { value: 'Aluguel novo' } })
      fireEvent.submit(form)
      await waitFor(() => expect(chamadasA('PUT /lancamentos/o1')).toHaveLength(1))
      expect(JSON.parse(String(corpoEnviado))).toMatchObject({
        titulo: 'Aluguel novo',
        valorCentavos: 150000,
        data: '2026-10-25',
      })
    })

    it('apagar realizada de série ativa de hoje em diante avisa que volta como prevista', async () => {
      mockFetch({
        ...base,
        'GET /recorrencias': responde([recorrenciaAtiva]),
        [MES_ATUAL]: responde([prevista({ status: 'realizado' })]),
        'DELETE /lancamentos/o1': () => Promise.resolve(new Response(null, { status: 204 })),
      })
      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: 'Apagar Aluguel' }))
      expect(screen.getByText(/volta como prevista/i)).toBeVisible()
      fireEvent.click(screen.getByRole('button', { name: 'Confirmar apagar Aluguel' }))
      await waitFor(() => expect(chamadasA('DELETE /lancamentos/o1')).toHaveLength(1))
      await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
    })

    it('apagar realizada de antes de hoje ou de série desativada não avisa', async () => {
      mockFetch({
        ...base,
        'GET /recorrencias': responde([recorrenciaAtiva]),
        [MES_ATUAL]: responde([
          prevista({ status: 'realizado', dataCaixa: '2026-10-10', dataCompetencia: '2026-10-10' }),
          prevista({ id: 'o3', titulo: 'Seguro', status: 'cancelado', templateId: 'r9' }),
        ]),
      })
      render(<App />)
      fireEvent.click(await screen.findByRole('button', { name: 'Apagar Aluguel' }))
      expect(screen.queryByText(/volta como prevista/i)).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: 'Cancelar' }))
      fireEvent.click(screen.getByRole('button', { name: 'Apagar Seguro' }))
      expect(screen.queryByText(/volta como prevista/i)).toBeNull()
    })
  })

  describe('confirmar com ajuste e vencidas', () => {
    const vencida = (sobra: Partial<Record<string, unknown>> = {}) =>
      prevista({ id: 'v1', titulo: 'Internet', valorCentavos: 9990, dataCompetencia: '2026-09-05', dataCaixa: '2026-09-05', ...sobra })
    const vencidasCom = (lancamentos: unknown[], maisAlgum = false) =>
      responde({ lancamentos, maisAlgum })
    const VENCIDAS = 'GET /lancamentos/vencidos'
    const blocoVencidas = async (): Promise<HTMLElement> =>
      screen.findByRole('region', { name: /vencidas/i })

    function patchQueCaptura(id: string, guarda: { corpo?: unknown }) {
      return {
        [`PATCH /lancamentos/${id}`]: (corpo: unknown) => {
          guarda.corpo = corpo
          return Promise.resolve(jsonResponse(prevista({ id, status: 'realizado' }), 200))
        },
      }
    }

    it('confirmar abre valor e data preenchidos com o previsto', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista()]) })
      render(<App />)
      const form = await abrirConfirmacao('Aluguel')
      expect(within(form).getByLabelText(/valor em reais/i)).toHaveValue('1.500,00')
      expect(within(form).getByLabelText('Data')).toHaveValue('2026-10-25')
      expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(0)
    })

    it('Voltar fecha o formulário sem enviar', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista()]) })
      render(<App />)
      const form = await abrirConfirmacao('Aluguel')
      fireEvent.click(within(form).getByRole('button', { name: 'Voltar' }))
      expect(screen.getByRole('button', { name: 'Confirmar Aluguel' })).toBeVisible()
      expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(0)
    })

    it('mudando o valor manda valorCentavos inteiro e só ele', async () => {
      const guarda: { corpo?: unknown } = {}
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista()]), ...patchQueCaptura('o1', guarda) })
      render(<App />)
      const form = await abrirConfirmacao('Aluguel')
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: '1.499,90' } })
      enviarConfirmacao(form)
      await waitFor(() => expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(1))
      expect(JSON.parse(String(guarda.corpo))).toEqual({ status: 'realizado', valorCentavos: 149990 })
    })

    it('mudando a data manda a data local AAAA-MM-DD e só ela', async () => {
      const guarda: { corpo?: unknown } = {}
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista()]), ...patchQueCaptura('o1', guarda) })
      render(<App />)
      const form = await abrirConfirmacao('Aluguel')
      fireEvent.change(within(form).getByLabelText('Data'), { target: { value: '2026-10-12' } })
      enviarConfirmacao(form)
      await waitFor(() => expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(1))
      expect(JSON.parse(String(guarda.corpo))).toEqual({ status: 'realizado', data: '2026-10-12' })
    })

    it('valor inválido não envia e mostra a mensagem', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista()]) })
      render(<App />)
      const form = await abrirConfirmacao('Aluguel')
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: 'abc' } })
      enviarConfirmacao(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent(/valor inválido/i)
      expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(0)
    })

    it('data fora da faixa não envia', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista()]) })
      render(<App />)
      const form = await abrirConfirmacao('Aluguel')
      fireEvent.change(within(form).getByLabelText('Data'), { target: { value: '1800-01-01' } })
      // O clique esbarraria na validação nativa do min; o submit direto exercita a checagem do código.
      fireEvent.submit(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent(/data inválida/i)
      expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(0)
    })

    it('400 do servidor aparece dentro do formulário de ajuste', async () => {
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([prevista()]),
        'PATCH /lancamentos/o1': responde({ erro: 'saldo da conta fora do limite' }, 400),
      })
      render(<App />)
      const form = await abrirConfirmacao('Aluguel')
      fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: '10,00' } })
      enviarConfirmacao(form)
      expect(await within(form).findByRole('alert')).toHaveTextContent('saldo da conta fora do limite')
    })

    it('submit duplo manda uma só requisição', async () => {
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([prevista()]),
        'PATCH /lancamentos/o1': () => new Promise<Response>(() => {}),
      })
      render(<App />)
      const form = await abrirConfirmacao('Aluguel')
      fireEvent.submit(form)
      fireEvent.submit(form)
      await waitFor(() => expect(chamadasA('PATCH /lancamentos/o1')).toHaveLength(1))
    })

    it('sem vencidas o bloco não aparece', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([]) })
      render(<App />)
      await screen.findByText(/nenhum lançamento neste mês/i)
      expect(screen.queryByRole('region', { name: /vencidas/i })).toBeNull()
    })

    it('mostra o bloco com contador, data completa, conta e valor com sinal', async () => {
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([]),
        [VENCIDAS]: vencidasCom([vencida(), vencida({ id: 'v2', titulo: 'Bônus', tipo: 'receita', valorCentavos: 20000 })]),
      })
      render(<App />)
      const bloco = within(await blocoVencidas())
      expect(bloco.getByRole('heading', { name: 'Vencidas (2)' })).toBeVisible()
      const itens = bloco.getAllByRole('listitem')
      expect(itens[0]).toHaveTextContent('05/09/2026')
      expect(itens[0]).toHaveTextContent('Internet')
      expect(itens[0]).toHaveTextContent('Nubank')
      expect(itens[0]).toHaveTextContent(texto('− R$ 99,90'))
      expect(itens[1]).toHaveTextContent(texto('+ R$ 200,00'))
      expect(bloco.queryByText(/mostrando as 200 mais antigas/i)).toBeNull()
    })

    it('maisAlgum mostra o aviso de corte', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([]), [VENCIDAS]: vencidasCom([vencida()], true) })
      render(<App />)
      expect(within(await blocoVencidas()).getByText('mostrando as 200 mais antigas')).toBeVisible()
    })

    it('confirmar vencida sem mudar nada manda só status e recarrega vencidas, mês e saldo', async () => {
      const guarda: { corpo?: unknown } = {}
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([]),
        [VENCIDAS]: vencidasCom([vencida()]),
        ...patchQueCaptura('v1', guarda),
      })
      render(<App />)
      const bloco = within(await blocoVencidas())
      fireEvent.click(bloco.getByRole('button', { name: 'Confirmar Internet' }))
      const form = await screen.findByRole('form', { name: 'Confirmar Internet' })
      expect(within(form).getByLabelText('Data')).toHaveValue('2026-09-05')
      enviarConfirmacao(form)
      await waitFor(() => expect(chamadasA('PATCH /lancamentos/v1')).toHaveLength(1))
      expect(JSON.parse(String(guarda.corpo))).toEqual({ status: 'realizado' })
      await waitFor(() => expect(chamadasA(VENCIDAS)).toHaveLength(2))
      await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
      await waitFor(() => expect(chamadasA('GET /contas')).toHaveLength(2))
    })

    it('cancelar vencida pede confirmação antes de enviar', async () => {
      const guarda: { corpo?: unknown } = {}
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([]),
        [VENCIDAS]: vencidasCom([vencida()]),
        ...patchQueCaptura('v1', guarda),
      })
      render(<App />)
      const bloco = within(await blocoVencidas())
      fireEvent.click(bloco.getByRole('button', { name: 'Cancelar Internet' }))
      expect(chamadasA('PATCH /lancamentos/v1')).toHaveLength(0)
      fireEvent.click(bloco.getByRole('button', { name: 'Confirmar cancelar Internet' }))
      await waitFor(() => expect(chamadasA('PATCH /lancamentos/v1')).toHaveLength(1))
      expect(JSON.parse(String(guarda.corpo))).toEqual({ status: 'cancelado' })
      await waitFor(() => expect(chamadasA(VENCIDAS)).toHaveLength(2))
    })

    it('Editar vencida abre o formulário de edição', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([]), [VENCIDAS]: vencidasCom([vencida()]) })
      render(<App />)
      fireEvent.click(within(await blocoVencidas()).getByRole('button', { name: 'Editar Internet' }))
      const form = await formulario('Editar lançamento')
      expect(within(form).getByLabelText('Título')).toHaveValue('Internet')
    })

    it('falha na busca de vencidas mostra aviso, não derruba o mês e tenta de novo', async () => {
      let chamadas = 0
      mockFetch({
        ...base,
        [MES_ATUAL]: responde([lancamento()]),
        [VENCIDAS]: () => {
          chamadas += 1
          return Promise.resolve(
            chamadas === 1
              ? jsonResponse({ erro: 'x' }, 500)
              : jsonResponse({ lancamentos: [vencida()], maisAlgum: false }, 200),
          )
        },
      })
      render(<App />)
      const area = within(await secao())
      expect(await area.findByText('Feira')).toBeVisible()
      expect(await area.findByText('Não foi possível carregar as vencidas.')).toBeVisible()
      fireEvent.click(area.getByRole('button', { name: /tentar carregar as vencidas de novo/i }))
      expect(await blocoVencidas()).toBeVisible()
      expect(screen.queryByText('Não foi possível carregar as vencidas.')).toBeNull()
    })
  })

  describe('parcelas de uma divisão', () => {
    const divisaoDeDuas = {
      modo: 'valor',
      parcelas: [
        { ordem: 1, diaDoMes: 15, valorCentavos: 100000, valorParcelaCentavos: 100000 },
        { ordem: 2, diaDoMes: 25, valorCentavos: 50000, valorParcelaCentavos: 50000 },
      ],
    }

    it('mostra "parcela 1 de 2" quando a recorrência traz a divisão', async () => {
      mockFetch({
        ...base,
        'GET /recorrencias': responde([{ ...recorrenciaAtiva, divisao: divisaoDeDuas }]),
        [MES_ATUAL]: responde([prevista({ parcelaOrdem: 1 }), prevista({ id: 'o2', parcelaOrdem: 2 })]),
      })
      render(<App />)
      expect(await screen.findByText('parcela 1 de 2')).toBeVisible()
      expect(screen.getByText('parcela 2 de 2')).toBeVisible()
    })

    it('mostra só "parcela 1" quando a recorrência não está disponível', async () => {
      mockFetch({ ...base, [MES_ATUAL]: responde([prevista({ parcelaOrdem: 1 })]) })
      render(<App />)
      expect(await screen.findByText('parcela 1')).toBeVisible()
    })

    it('ocorrência sem divisão não mostra parcela', async () => {
      mockFetch({ ...base, 'GET /recorrencias': responde([recorrenciaAtiva]), [MES_ATUAL]: responde([prevista()]) })
      render(<App />)
      await screen.findByRole('button', { name: 'Confirmar Aluguel' })
      expect(screen.queryByText(/^parcela/)).toBeNull()
    })
  })
})
