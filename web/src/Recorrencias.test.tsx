import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import App from './App'

type Rotas = Partial<Record<string, (corpo: unknown) => Promise<Response>>>

const usuario = { id: '7f1e2c9a-0b6d-4f43-9a52-1c8d3e5b6a70', email: 'ana@exemplo.com', nome: 'Ana' }

const conta = {
  id: 'c1',
  nome: 'Nubank',
  tipo: 'corrente',
  moeda: 'BRL',
  saldoInicialCentavos: 0,
  saldoCentavos: 100000,
  arquivada: false,
  criadaEm: '2026-10-19T12:00:00Z',
}

const categorias = [
  { id: 'cd1', nome: 'Moradia', tipo: 'despesa', paiId: null, cor: null, icone: null },
  { id: 'cr1', nome: 'Salário', tipo: 'receita', paiId: null, cor: null, icone: null },
]

function recorrencia(sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> {
  return {
    id: 'r1',
    tipo: 'despesa',
    titulo: 'Aluguel',
    descricao: null,
    valorCentavos: 150000,
    categoriaId: 'cd1',
    contaId: 'c1',
    ativo: true,
    criadoEm: '2026-10-01T12:00:00Z',
    regra: {
      frequencia: 'mensal',
      intervalo: 1,
      diaDoMes: 5,
      diaDaSemana: null,
      inicio: '2026-11-05',
      fim: null,
      ocorrenciasMax: null,
    },
    proximaOcorrencia: '2026-11-05',
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
  'GET /contas': responde([conta]),
  'GET /categorias': responde(categorias),
  [MES_ATUAL]: responde([]),
  'GET /recorrencias': responde([]),
}

const secao = async (): Promise<HTMLElement> => screen.findByRole('region', { name: 'Recorrências' })
const formulario = async (): Promise<HTMLElement> => screen.findByRole('form', { name: 'Nova recorrência' })

function preencher(form: HTMLElement, titulo: string, valor: string): void {
  fireEvent.change(within(form).getByLabelText('Título'), { target: { value: titulo } })
  fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: valor } })
}

function mudar(form: HTMLElement, rotulo: string, valor: string): void {
  fireEvent.change(within(form).getByLabelText(rotulo), { target: { value: valor } })
}

const criada = { recorrencia: recorrencia(), ocorrenciasMaterializadas: 12 }

describe('Recorrencias', () => {
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
    mockFetch({ ...base, 'GET /recorrencias': () => new Promise<Response>(() => {}) })
    render(<App />)
    expect(await within(await secao()).findByText(/carregando recorrências/i)).toBeVisible()
  })

  it('lista vazia mostra "Nenhuma recorrência."', async () => {
    mockFetch(base)
    render(<App />)
    expect(await within(await secao()).findByText('Nenhuma recorrência.')).toBeVisible()
  })

  it('erro mostra alerta e "Tentar de novo" busca outra vez', async () => {
    mockFetch({ ...base, 'GET /recorrencias': responde({ erro: 'x' }, 500) })
    render(<App />)
    const area = within(await secao())
    expect(await area.findByText('Não foi possível carregar as recorrências.')).toBeVisible()
    fireEvent.click(area.getByRole('button', { name: /tentar de novo/i }))
    await waitFor(() => expect(chamadasA('GET /recorrencias')).toHaveLength(2))
  })

  it('503 mostra serviço indisponível', async () => {
    mockFetch({ ...base, 'GET /recorrencias': responde({ erro: 'x' }, 503) })
    render(<App />)
    expect(await within(await secao()).findByText(/serviço indisponível/i)).toBeVisible()
  })

  it('401 na lista volta para o login', async () => {
    mockFetch({ ...base, 'GET /recorrencias': responde({ erro: 'x' }, 401) })
    render(<App />)
    expect(await screen.findByRole('status')).toHaveTextContent(/sessão acabou/i)
  })

  it('lista com sinal, conta, regra legível, próxima ocorrência e marca de desativada', async () => {
    mockFetch({
      ...base,
      'GET /recorrencias': responde([
        recorrencia(),
        recorrencia({
          id: 'r2',
          tipo: 'receita',
          titulo: 'Salário',
          categoriaId: 'cr1',
          valorCentavos: 500000,
          proximaOcorrencia: null,
          regra: {
            frequencia: 'semanal',
            intervalo: 2,
            diaDoMes: null,
            diaDaSemana: 0,
            inicio: '2026-11-01',
            fim: '2027-01-31',
            ocorrenciasMax: null,
          },
        }),
        recorrencia({ id: 'r3', titulo: 'Antiga', ativo: false }),
      ]),
    })
    render(<App />)
    const area = within(await secao())
    const itens = await area.findAllByRole('listitem')
    expect(itens).toHaveLength(3)
    expect(itens[0]).toHaveTextContent('Aluguel')
    expect(itens[0]).toHaveTextContent(/− R\$[\s ]1\.500,00/)
    expect(itens[0]).toHaveTextContent('Nubank')
    expect(itens[0]).toHaveTextContent('Todo mês no dia 5')
    expect(itens[0]).toHaveTextContent('Próxima: 05/11/2026')
    expect(itens[1]).toHaveTextContent(/\+ R\$[\s ]5\.000,00/)
    expect(itens[1]).toHaveTextContent('A cada 2 semanas no domingo, até 31/01/2027')
    expect(itens[1]).toHaveTextContent('Sem próximas')
    expect(itens[2]).toHaveTextContent('Desativada')
    expect(within(itens[2]).queryByRole('button', { name: /desativar/i })).toBeNull()
  })

  it('desativar pede confirmação, envia PATCH ativo false e recarrega as listas', async () => {
    let corpoEnviado: unknown
    mockFetch({
      ...base,
      'GET /recorrencias': responde([recorrencia()]),
      'PATCH /recorrencias/r1': (corpo) => {
        corpoEnviado = corpo
        return Promise.resolve(jsonResponse(recorrencia({ ativo: false }), 200))
      },
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Desativar Aluguel' }))
    expect(screen.getByText(/previstas de hoje em diante somem/i)).toBeVisible()
    expect(chamadasA('PATCH /recorrencias/r1')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar desativar Aluguel' }))
    await waitFor(() => expect(chamadasA('PATCH /recorrencias/r1')).toHaveLength(1))
    expect(JSON.parse(String(corpoEnviado))).toEqual({ ativo: false })
    await waitFor(() => expect(chamadasA('GET /recorrencias')).toHaveLength(2))
    await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
    await waitFor(() => expect(chamadasA('GET /contas')).toHaveLength(2))
  })

  it('manter não desativa', async () => {
    mockFetch({ ...base, 'GET /recorrencias': responde([recorrencia()]) })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Desativar Aluguel' }))
    fireEvent.click(screen.getByRole('button', { name: 'Manter' }))
    expect(screen.getByRole('button', { name: 'Desativar Aluguel' })).toBeVisible()
    expect(chamadasA('PATCH /recorrencias/r1')).toHaveLength(0)
  })

  it('404 ao desativar avisa que não existe mais e recarrega', async () => {
    mockFetch({
      ...base,
      'GET /recorrencias': responde([recorrencia()]),
      'PATCH /recorrencias/r1': responde({ erro: 'x' }, 404),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Desativar Aluguel' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar desativar Aluguel' }))
    expect(await screen.findByText('Essa recorrência não existe mais.')).toBeVisible()
    await waitFor(() => expect(chamadasA('GET /recorrencias')).toHaveLength(2))
  })

  it('401 ao desativar volta para o login', async () => {
    mockFetch({
      ...base,
      'GET /recorrencias': responde([recorrencia()]),
      'PATCH /recorrencias/r1': responde({ erro: 'x' }, 401),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Desativar Aluguel' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirmar desativar Aluguel' }))
    expect(await screen.findByRole('status')).toHaveTextContent(/sessão acabou/i)
  })

  it('cria mensal com valor em centavos e datas locais, sem diaDaSemana, e recarrega tudo', async () => {
    let corpoEnviado: unknown
    mockFetch({
      ...base,
      'POST /recorrencias': (corpo) => {
        corpoEnviado = corpo
        return Promise.resolve(jsonResponse(criada, 201))
      },
    })
    render(<App />)
    const form = await formulario()
    preencher(form, '  Aluguel  ', '1.500,00')
    fireEvent.change(within(form).getByLabelText(/descrição/i), { target: { value: 'apto' } })
    mudar(form, 'Dia do mês', '31')
    fireEvent.submit(form)
    await waitFor(() => expect(chamadasA('POST /recorrencias')).toHaveLength(1))
    expect(JSON.parse(String(corpoEnviado))).toEqual({
      tipo: 'despesa',
      titulo: 'Aluguel',
      descricao: 'apto',
      valorCentavos: 150000,
      categoriaId: 'cd1',
      contaId: 'c1',
      regra: { frequencia: 'mensal', intervalo: 1, diaDoMes: 31, inicio: '2026-10-19' },
    })
    await waitFor(() => expect(chamadasA('GET /recorrencias')).toHaveLength(2))
    await waitFor(() => expect(chamadasA(MES_ATUAL)).toHaveLength(2))
    await waitFor(() => expect(chamadasA('GET /contas')).toHaveLength(2))
    await waitFor(async () => expect(within(await formulario()).getByLabelText('Título')).toHaveValue(''))
  })

  it('mostra a nota dos meses curtos e só o campo que a frequência usa', async () => {
    mockFetch(base)
    render(<App />)
    const form = await formulario()
    expect(within(form).getByLabelText('Dia do mês')).toHaveValue(19)
    expect(within(form).getByText(/meses curtos usam o último dia/i)).toBeVisible()
    expect(within(form).queryByLabelText('Dia da semana')).toBeNull()
    mudar(form, 'Frequência', 'semanal')
    expect(within(form).queryByLabelText('Dia do mês')).toBeNull()
    expect(within(form).getByLabelText('Dia da semana')).toBeVisible()
    mudar(form, 'Frequência', 'diaria')
    expect(within(form).queryByLabelText('Dia do mês')).toBeNull()
    expect(within(form).queryByLabelText('Dia da semana')).toBeNull()
  })

  it('cria semanal com diaDaSemana e sem diaDoMes', async () => {
    let corpoEnviado: unknown
    mockFetch({
      ...base,
      'POST /recorrencias': (corpo) => {
        corpoEnviado = corpo
        return Promise.resolve(jsonResponse(criada, 201))
      },
    })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Feira', '80,00')
    mudar(form, 'Frequência', 'semanal')
    mudar(form, 'Dia da semana', '0')
    mudar(form, 'Intervalo', '2')
    fireEvent.submit(form)
    await waitFor(() => expect(chamadasA('POST /recorrencias')).toHaveLength(1))
    expect(JSON.parse(String(corpoEnviado)).regra).toEqual({
      frequencia: 'semanal',
      intervalo: 2,
      diaDaSemana: 0,
      inicio: '2026-10-19',
    })
  })

  it('anual e diária não mandam dia do mês nem da semana', async () => {
    const corpos: unknown[] = []
    mockFetch({
      ...base,
      'POST /recorrencias': (corpo) => {
        corpos.push(JSON.parse(String(corpo)))
        return Promise.resolve(jsonResponse(criada, 201))
      },
    })
    render(<App />)
    let form = await formulario()
    preencher(form, 'Seguro', '10,00')
    mudar(form, 'Frequência', 'anual')
    fireEvent.submit(form)
    await waitFor(() => expect(corpos).toHaveLength(1))
    // O formulário remonta depois de salvar.
    await waitFor(async () => expect(within(await formulario()).getByLabelText('Título')).toHaveValue(''))
    form = await formulario()
    preencher(form, 'Café', '5,00')
    mudar(form, 'Frequência', 'diaria')
    fireEvent.submit(form)
    await waitFor(() => expect(corpos).toHaveLength(2))
    expect(corpos.map((c) => (c as { regra: unknown }).regra)).toEqual([
      { frequencia: 'anual', intervalo: 1, inicio: '2026-10-19' },
      { frequencia: 'diaria', intervalo: 1, inicio: '2026-10-19' },
    ])
  })

  it('"N vezes" manda ocorrenciasMax e nunca fim', async () => {
    let corpoEnviado: unknown
    mockFetch({
      ...base,
      'POST /recorrencias': (corpo) => {
        corpoEnviado = corpo
        return Promise.resolve(jsonResponse(criada, 201))
      },
    })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Parcela', '100,00')
    // Digita a data final antes de trocar, para provar que o valor escondido não vaza.
    mudar(form, 'Término', 'data')
    mudar(form, 'Data final', '2027-01-01')
    mudar(form, 'Término', 'vezes')
    mudar(form, 'Número de vezes', '10')
    fireEvent.submit(form)
    await waitFor(() => expect(chamadasA('POST /recorrencias')).toHaveLength(1))
    const regra = JSON.parse(String(corpoEnviado)).regra
    expect(regra.ocorrenciasMax).toBe(10)
    expect(regra).not.toHaveProperty('fim')
  })

  it('"até a data" manda fim e nunca ocorrenciasMax', async () => {
    let corpoEnviado: unknown
    mockFetch({
      ...base,
      'POST /recorrencias': (corpo) => {
        corpoEnviado = corpo
        return Promise.resolve(jsonResponse(criada, 201))
      },
    })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Parcela', '100,00')
    mudar(form, 'Término', 'vezes')
    mudar(form, 'Número de vezes', '10')
    mudar(form, 'Término', 'data')
    mudar(form, 'Data final', '2027-01-01')
    fireEvent.submit(form)
    await waitFor(() => expect(chamadasA('POST /recorrencias')).toHaveLength(1))
    const regra = JSON.parse(String(corpoEnviado)).regra
    expect(regra.fim).toBe('2027-01-01')
    expect(regra).not.toHaveProperty('ocorrenciasMax')
  })

  it('trocar o tipo troca as categorias', async () => {
    mockFetch(base)
    render(<App />)
    const form = await formulario()
    const select = within(form).getByLabelText('Categoria')
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Moradia'])
    mudar(form, 'Tipo', 'receita')
    expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['Salário'])
  })

  it.each([
    ['valor zero', 'valor', '0', /valor inválido/i],
    ['valor com texto', 'valor', 'abc', /valor inválido/i],
    ['intervalo zero', 'Intervalo', '0', /intervalo/i],
    ['intervalo 100', 'Intervalo', '100', /intervalo/i],
    ['intervalo decimal', 'Intervalo', '1,5', /intervalo/i],
    ['dia do mês 32', 'Dia do mês', '32', /dia do mês/i],
    ['dia do mês 0', 'Dia do mês', '0', /dia do mês/i],
  ])('%s não envia', async (_nome, campo, valor, mensagem) => {
    mockFetch(base)
    render(<App />)
    const form = await formulario()
    preencher(form, 'Aluguel', '10,00')
    if (campo === 'valor') fireEvent.change(within(form).getByLabelText(/valor em reais/i), { target: { value: valor } })
    else mudar(form, campo, valor)
    fireEvent.submit(form)
    expect(await within(form).findByRole('alert')).toHaveTextContent(mensagem)
    expect(chamadasA('POST /recorrencias')).toHaveLength(0)
  })

  it.each([
    ['fim anterior ao início', 'data', 'Data final', '2026-10-01', /data final/i],
    ['vezes zero', 'vezes', 'Número de vezes', '0', /número de vezes/i],
    ['vezes acima de 10000', 'vezes', 'Número de vezes', '10001', /número de vezes/i],
    ['vezes vazio', 'vezes', 'Número de vezes', '', /número de vezes/i],
    ['data final vazia', 'data', 'Data final', '', /data final/i],
  ])('%s não envia', async (_nome, termino, campo, valor, mensagem) => {
    mockFetch(base)
    render(<App />)
    const form = await formulario()
    preencher(form, 'Aluguel', '10,00')
    mudar(form, 'Término', termino)
    mudar(form, campo, valor)
    fireEvent.submit(form)
    expect(await within(form).findByRole('alert')).toHaveTextContent(mensagem)
    expect(chamadasA('POST /recorrencias')).toHaveLength(0)
  })

  it('400 mostra a mensagem do servidor e mantém o que foi digitado', async () => {
    mockFetch({ ...base, 'POST /recorrencias': responde({ erro: 'conta arquivada' }, 400) })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Aluguel', '10,00')
    fireEvent.submit(form)
    expect(await within(form).findByRole('alert')).toHaveTextContent('conta arquivada')
    expect(within(form).getByLabelText('Título')).toHaveValue('Aluguel')
  })

  it('503 ao criar mostra serviço indisponível', async () => {
    mockFetch({ ...base, 'POST /recorrencias': responde({ erro: 'x' }, 503) })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Aluguel', '10,00')
    fireEvent.submit(form)
    expect(await within(form).findByRole('alert')).toHaveTextContent('Serviço indisponível. Tente de novo em instantes.')
  })

  it('401 ao criar volta para o login', async () => {
    mockFetch({ ...base, 'POST /recorrencias': responde({ erro: 'x' }, 401) })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Aluguel', '10,00')
    fireEvent.submit(form)
    expect(await screen.findByRole('status')).toHaveTextContent(/sessão acabou/i)
  })

  it('submit duplo manda uma só requisição', async () => {
    mockFetch({ ...base, 'POST /recorrencias': () => new Promise<Response>(() => {}) })
    render(<App />)
    const form = await formulario()
    preencher(form, 'Aluguel', '10,00')
    fireEvent.submit(form)
    fireEvent.submit(form)
    expect(chamadasA('POST /recorrencias')).toHaveLength(1)
    expect(within(form).getByRole('button', { name: /salvando/i })).toBeDisabled()
  })

  it('sem conta ativa o formulário pede para criar uma conta', async () => {
    mockFetch({ ...base, 'GET /contas': responde([]) })
    render(<App />)
    expect(await within(await secao()).findByText('Crie uma conta antes de criar uma recorrência.')).toBeVisible()
  })
})
