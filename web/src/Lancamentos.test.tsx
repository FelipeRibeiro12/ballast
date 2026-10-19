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
    contaId: 'c1',
    dataCompetencia: '2026-10-05',
    dataCaixa: '2026-10-05',
    criadoEm: '2026-10-05T12:00:00Z',
    ...sobra,
  }
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
})
