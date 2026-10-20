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
    saldoInicialCentavos: 123456,
    saldoCentavos: 123456,
    arquivada: false,
    criadaEm: '2026-10-19T12:00:00Z',
    cartao: null,
    ...sobra,
  }
}

function jsonResponse(corpo: unknown, status: number): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } })
}

const responde = (corpo: unknown, status = 200) => () => Promise.resolve(jsonResponse(corpo, status))

// A tela logada também busca o saldo projetado; estes testes não querem saber dele.
const saldoProjetadoPadrao = {
  inicio: '2026-10-20',
  fim: '2026-10-20',
  contaId: null,
  saldoAtualCentavos: 0,
  vencidasCentavos: 0,
  menorSaldo: { data: '2026-10-20', saldoCentavos: 0 },
  dias: [{ data: '2026-10-20', entradasCentavos: 0, saidasCentavos: 0, saldoCentavos: 0 }],
}

function mockFetch(rotas: Rotas): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const rota = rotas[chave]
      if (rota) return rota(init?.body)
      if (chave === 'GET /healthz') return Promise.resolve(jsonResponse({ mensagem: 'ok' }, 200))
      // A tela logada também busca categorias e lançamentos; estes testes não querem saber deles.
      if (chave === 'GET /categorias') return Promise.resolve(jsonResponse([], 200))
      if (chave === 'GET /recorrencias') return Promise.resolve(jsonResponse([], 200))
      if (chave.startsWith('GET /lancamentos?')) return Promise.resolve(jsonResponse([], 200))
      if (chave === 'GET /lancamentos/vencidos') {
        return Promise.resolve(jsonResponse({ lancamentos: [], maisAlgum: false }, 200))
      }
      if (chave.startsWith('GET /saldo-projetado?')) {
        return Promise.resolve(jsonResponse(saldoProjetadoPadrao, 200))
      }
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

// O Intl separa símbolo e número com espaço sem quebra; o texto do teste usa espaço comum.
const texto = (valor: string): RegExp => new RegExp(valor.replace(/[.*+?^${}()|[\]\\]/g, '\\$&').replace(' ', '[\\s\\u00a0]'))

function preencherNovaConta(nome: string, saldo = ''): void {
  fireEvent.change(screen.getByLabelText('Nome'), { target: { value: nome } })
  fireEvent.change(screen.getByLabelText(/saldo inicial/i), { target: { value: saldo } })
}

const enviarNovaConta = (): void => {
  fireEvent.submit(screen.getByRole('form', { name: /nova conta/i }))
}

// A tela de lançamentos também lista o nome das contas nos selects, então a busca fica na seção de contas.
const secaoContas = (): HTMLElement => screen.getByRole('region', { name: /^contas/i })
const contasNubank = async (): Promise<HTMLElement> =>
  within(await screen.findByRole('region', { name: /^contas/i })).findByText('Nubank')

describe('Contas', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('mostra carregando enquanto a lista não chega', async () => {
    mockFetch({ ...sessaoViva, 'GET /contas': () => new Promise<Response>(() => {}) })
    render(<App />)
    expect(await screen.findByText(/carregando contas/i)).toBeVisible()
  })

  it('mostra nome, tipo, saldo formatado e o total', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([
        conta(),
        conta({ id: 'c2', nome: 'Carteira', tipo: 'dinheiro', saldoCentavos: -3456 }),
        conta({ id: 'c3', nome: 'Reserva', tipo: 'poupanca', saldoCentavos: 1000 }),
      ]),
    })
    render(<App />)
    const lista = await screen.findByRole('list')
    const itens = within(lista).getAllByRole('listitem')
    expect(itens).toHaveLength(3)
    expect(itens[0]).toHaveTextContent('Nubank')
    expect(itens[0]).toHaveTextContent('Corrente')
    expect(itens[0].textContent).toMatch(texto('R$ 1.234,56'))
    expect(itens[1]).toHaveTextContent('Dinheiro')
    expect(itens[1].textContent).toMatch(texto('-R$ 34,56'))
    expect(itens[2]).toHaveTextContent('Poupança')
    // 123456 - 3456 + 1000 = 121000
    expect(screen.getByText(/total/i).textContent).toMatch(texto('R$ 1.210,00'))
    expect(chamadasA('GET /contas')[0][1]).toMatchObject({ credentials: 'same-origin' })
  })

  it('soma o total em inteiro exato mesmo passando do limite seguro', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([
        conta({ saldoCentavos: 9007199254740991 }),
        conta({ id: 'c2', nome: 'Outra', saldoCentavos: 9007199254740991 }),
      ]),
    })
    render(<App />)
    await screen.findByRole('list')
    expect(screen.getByText(/total/i).textContent).toMatch(texto('R$ 180.143.985.094.819,82'))
  })

  it('lista vazia mostra "Nenhuma conta ainda."', async () => {
    mockFetch({ ...sessaoViva, 'GET /contas': responde([]) })
    render(<App />)
    expect(await screen.findByText('Nenhuma conta ainda.')).toBeVisible()
  })

  it('erro mostra alerta e "Tentar de novo" busca outra vez', async () => {
    let chamadas = 0
    mockFetch({
      ...sessaoViva,
      'GET /contas': () => {
        chamadas += 1
        return Promise.resolve(chamadas === 1 ? jsonResponse({ erro: 'boom' }, 500) : jsonResponse([conta()], 200))
      },
    })
    render(<App />)
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Não foi possível carregar as contas.')
    expect(alerta).not.toHaveTextContent(/boom/)
    fireEvent.click(screen.getByRole('button', { name: /tentar de novo/i }))
    expect(await contasNubank()).toBeVisible()
  })

  it('503 mostra serviço indisponível', async () => {
    mockFetch({ ...sessaoViva, 'GET /contas': responde({ erro: 'x' }, 503) })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Serviço indisponível. Tente de novo em instantes.')
  })

  it('401 em /contas volta para o login', async () => {
    mockFetch({ ...sessaoViva, 'GET /contas': responde({ erro: 'x' }, 401) })
    render(<App />)
    expect(await screen.findByRole('form', { name: /entrar/i })).toBeVisible()
    expect(screen.getByRole('status')).toHaveTextContent(/sessão acabou/i)
  })

  it('401 ao criar volta para o login', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'POST /contas': responde({ erro: 'x' }, 401),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    preencherNovaConta('Itaú')
    enviarNovaConta()
    expect(await screen.findByRole('form', { name: /entrar/i })).toBeVisible()
  })

  it('declara os atributos do formulário', async () => {
    mockFetch({ ...sessaoViva, 'GET /contas': responde([]) })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    const nome = screen.getByLabelText('Nome')
    expect(nome).toHaveAttribute('maxlength', '100')
    expect(nome).toBeRequired()
    expect(screen.getByLabelText(/saldo inicial/i)).toHaveAttribute('inputmode', 'decimal')
    const opcoes = within(screen.getByLabelText('Tipo')).getAllByRole('option')
    expect(opcoes.map((opcao) => opcao.textContent)).toEqual(['Corrente', 'Poupança', 'Dinheiro', 'Cartão de crédito'])
  })

  it('cria a conta, mostra na lista sem recarregar e limpa o formulário', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'POST /contas': () =>
        Promise.resolve(
          jsonResponse(conta({ id: 'novo', nome: 'Itaú', tipo: 'poupanca', saldoCentavos: 123450 }), 201),
        ),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    preencherNovaConta('Itaú', '1.234,5')
    fireEvent.change(screen.getByLabelText('Tipo'), { target: { value: 'poupanca' } })
    enviarNovaConta()
    const item = await screen.findByRole('listitem')
    expect(item).toHaveTextContent('Itaú')
    expect(item.textContent).toMatch(texto('R$ 1.234,50'))
    expect(screen.queryByText('Nenhuma conta ainda.')).toBeNull()
    expect(screen.getByLabelText('Nome')).toHaveValue('')
    expect(screen.getByLabelText(/saldo inicial/i)).toHaveValue('')
    const [url, init] = chamadasA('POST /contas')[0]
    expect(url).toBe('/contas')
    expect(init).toMatchObject({ credentials: 'same-origin', headers: { 'Content-Type': 'application/json' } })
    expect(JSON.parse(String(init?.body))).toEqual({ nome: 'Itaú', tipo: 'poupanca', saldoInicialCentavos: 123450 })
    expect(chamadasA('GET /contas')).toHaveLength(1)
  })

  it('saldo vazio vale zero', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'POST /contas': responde(conta({ saldoCentavos: 0 }), 201),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    preencherNovaConta('Itaú')
    enviarNovaConta()
    await screen.findByRole('listitem')
    expect(JSON.parse(String(chamadasA('POST /contas')[0][1]?.body))).toMatchObject({ saldoInicialCentavos: 0 })
  })

  it.each([['abc'], ['1,234'], ['90.071.992.547.409,92']])('saldo inválido "%s" não envia e avisa', async (saldo) => {
    mockFetch({ ...sessaoViva, 'GET /contas': responde([]) })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    preencherNovaConta('Itaú', saldo)
    enviarNovaConta()
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent(/saldo inválido/i)
    expect(chamadasA('POST /contas')).toHaveLength(0)
    expect(screen.getByLabelText(/saldo inicial/i)).toHaveAccessibleDescription(alerta.textContent ?? '')
  })

  it('409 mostra que o nome já existe e mantém o que foi digitado', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'POST /contas': responde({ erro: 'nome repetido' }, 409),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    preencherNovaConta('Itaú', '10')
    enviarNovaConta()
    expect(await screen.findByRole('alert')).toHaveTextContent('Já existe uma conta com esse nome.')
    expect(screen.getByLabelText('Nome')).toHaveValue('Itaú')
    expect(screen.getByRole('button', { name: /criar conta/i })).toBeEnabled()
  })

  it('400 mostra a mensagem do servidor', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'POST /contas': responde({ erro: 'nome com caractere inválido' }, 400),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    preencherNovaConta('Itaú')
    enviarNovaConta()
    expect(await screen.findByRole('alert')).toHaveTextContent('nome com caractere inválido')
  })

  it('500 ao criar mostra mensagem genérica sem o texto do servidor', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'POST /contas': responde({ erro: 'pq: syntax error' }, 500),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    preencherNovaConta('Itaú')
    enviarNovaConta()
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Não foi possível criar a conta. Tente de novo.')
    expect(alerta).not.toHaveTextContent(/pq:/)
  })

  it('desabilita o botão durante o envio e manda uma requisição só', async () => {
    let responder: (r: Response) => void = () => {}
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'POST /contas': () => new Promise<Response>((resolve) => { responder = resolve }),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    preencherNovaConta('Itaú')
    enviarNovaConta()
    expect(await screen.findByRole('button', { name: /criando/i })).toBeDisabled()
    enviarNovaConta()
    expect(chamadasA('POST /contas')).toHaveLength(1)
    responder(jsonResponse(conta({ nome: 'Itaú' }), 201))
    expect(await screen.findByRole('listitem')).toHaveTextContent('Itaú')
    expect(chamadasA('POST /contas')).toHaveLength(1)
  })

  it('arquivar tira a conta da lista e ela aparece em arquivadas, onde dá para desarquivar', async () => {
    const nubank = conta()
    let arquivada = false
    mockFetch({
      ...sessaoViva,
      'GET /contas': () => Promise.resolve(jsonResponse(arquivada ? [] : [nubank], 200)),
      'GET /contas?arquivadas=true': () => Promise.resolve(jsonResponse(arquivada ? [{ ...nubank, arquivada: true }] : [], 200)),
      'PATCH /contas/c1': (corpo) => {
        arquivada = JSON.parse(String(corpo)).arquivada === true
        return Promise.resolve(jsonResponse({ ...nubank, arquivada }, 200))
      },
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /arquivar nubank/i }))
    expect(await screen.findByText('Nenhuma conta ainda.')).toBeVisible()
    expect(within(secaoContas()).queryByText('Nubank')).toBeNull()
    expect(JSON.parse(String(chamadasA('PATCH /contas/c1')[0][1]?.body))).toEqual({ arquivada: true })

    fireEvent.click(screen.getByRole('button', { name: /ver arquivadas/i }))
    expect(await contasNubank()).toBeVisible()
    expect(screen.queryByRole('form', { name: /nova conta/i })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /desarquivar nubank/i }))
    expect(await screen.findByText('Nenhuma conta arquivada.')).toBeVisible()
    expect(JSON.parse(String(chamadasA('PATCH /contas/c1')[1][1]?.body))).toEqual({ arquivada: false })

    fireEvent.click(screen.getByRole('button', { name: /voltar às ativas/i }))
    expect(await contasNubank()).toBeVisible()
  })

  it('arquivadas vazias mostra "Nenhuma conta arquivada."', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([conta()]),
      'GET /contas?arquivadas=true': responde([]),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /ver arquivadas/i }))
    expect(await screen.findByText('Nenhuma conta arquivada.')).toBeVisible()
  })

  it('falha ao arquivar mantém a conta na lista e avisa', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([conta()]),
      'PATCH /contas/c1': responde({ erro: 'x' }, 500),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /arquivar nubank/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível alterar a conta. Tente de novo.')
    expect(within(secaoContas()).getByText('Nubank')).toBeVisible()
    await waitFor(() => expect(screen.getByRole('button', { name: /arquivar nubank/i })).toBeEnabled())
  })

  it('401 ao arquivar volta para o login', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([conta()]),
      'PATCH /contas/c1': responde({ erro: 'x' }, 401),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /arquivar nubank/i }))
    expect(await screen.findByRole('form', { name: /entrar/i })).toBeVisible()
  })

  it('404 ao arquivar avisa que a conta não existe mais e recarrega a lista', async () => {
    let chamadas = 0
    mockFetch({
      ...sessaoViva,
      'GET /contas': () => {
        chamadas += 1
        return Promise.resolve(jsonResponse(chamadas === 1 ? [conta()] : [], 200))
      },
      'PATCH /contas/c1': responde({ erro: 'x' }, 404),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /arquivar nubank/i }))
    expect(await screen.findByText('Nenhuma conta ainda.')).toBeVisible()
    expect(screen.getByRole('alert')).toHaveTextContent('Essa conta não existe mais.')
    expect(chamadasA('GET /contas')).toHaveLength(2)
    expect(within(secaoContas()).queryByText('Nubank')).toBeNull()
  })

  it('conta nova entra na ordem do servidor: nome sem caixa e id no empate', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([
        conta({ id: 'a1', nome: 'banco' }),
        conta({ id: 'a3', nome: 'Carteira' }),
      ]),
      'POST /contas': responde(conta({ id: 'a0', nome: 'Banco' }), 201),
    })
    render(<App />)
    await screen.findByText('Carteira')
    preencherNovaConta('Banco')
    enviarNovaConta()
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(3))
    const nomes = screen.getAllByRole('listitem').map((item) => item.querySelector('strong')?.textContent)
    // 'Banco' (a0) vem antes de 'banco' (a1) pelo id, e ambos antes de Carteira.
    expect(nomes).toEqual(['Banco', 'banco', 'Carteira'])
  })
})
