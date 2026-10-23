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
    categoriasPermitidas: null,
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

// O formulário abre num painel pelo botão "Nova conta" (ADR-033).
async function abrirNovaConta(): Promise<HTMLElement> {
  fireEvent.click(await screen.findByRole('button', { name: 'Nova conta' }))
  return screen.findByRole('form', { name: 'Nova conta' })
}

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

// As seções ficam atrás de rota por hash (ADR-033): cada arquivo abre a que testa.
beforeEach(() => {
  window.location.hash = '#/contas'
})

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
    await abrirNovaConta()
    preencherNovaConta('Itaú')
    enviarNovaConta()
    expect(await screen.findByRole('form', { name: /entrar/i })).toBeVisible()
  })

  it('declara os atributos do formulário', async () => {
    mockFetch({ ...sessaoViva, 'GET /contas': responde([]) })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovaConta()
    const nome = screen.getByLabelText('Nome')
    expect(nome).toHaveAttribute('maxlength', '100')
    expect(nome).toBeRequired()
    expect(screen.getByLabelText(/saldo inicial/i)).toHaveAttribute('inputmode', 'decimal')
    const opcoes = within(screen.getByLabelText('Tipo')).getAllByRole('option')
    expect(opcoes.map((opcao) => opcao.textContent)).toEqual([
      'Corrente',
      'Poupança',
      'Dinheiro',
      'Cartão de crédito',
      'Vale-benefício',
      'Cripto',
      'Investimento',
    ])
  })

  it('cria conta cripto sem campo de saldo e mostra o atalho para as posições', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'POST /contas': responde(conta({ nome: 'Exchange', tipo: 'cripto', saldoInicialCentavos: 0, saldoCentavos: 0 }), 201),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovaConta()
    fireEvent.change(screen.getByLabelText('Nome'), { target: { value: 'Exchange' } })
    fireEvent.change(screen.getByLabelText('Tipo'), { target: { value: 'cripto' } })
    expect(screen.queryByLabelText(/saldo inicial/i)).toBeNull()
    expect(screen.getByText('Compras, vendas e cotações da conta ficam na aba Investimentos.')).toBeVisible()
    enviarNovaConta()

    const link = await within(secaoContas()).findByRole('link', { name: 'Ver posições' })
    expect(link).toHaveAttribute('href', '#/investimentos')
    expect(JSON.parse(String(chamadasA('POST /contas')[0][1]?.body))).toEqual({
      nome: 'Exchange',
      tipo: 'cripto',
      saldoInicialCentavos: 0,
    })
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
    await abrirNovaConta()
    preencherNovaConta('Itaú', '1.234,5')
    fireEvent.change(screen.getByLabelText('Tipo'), { target: { value: 'poupanca' } })
    enviarNovaConta()
    const item = await screen.findByRole('listitem')
    expect(item).toHaveTextContent('Itaú')
    expect(item.textContent).toMatch(texto('R$ 1.234,50'))
    expect(screen.queryByText('Nenhuma conta ainda.')).toBeNull()
    // Salvou, o painel fecha; ao reabrir os campos voltam vazios.
    expect(screen.queryByRole('form', { name: 'Nova conta' })).toBeNull()
    await abrirNovaConta()
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
    await abrirNovaConta()
    preencherNovaConta('Itaú')
    enviarNovaConta()
    await screen.findByRole('listitem')
    expect(JSON.parse(String(chamadasA('POST /contas')[0][1]?.body))).toMatchObject({ saldoInicialCentavos: 0 })
  })

  it.each([['abc'], ['1,234'], ['90.071.992.547.409,92']])('saldo inválido "%s" não envia e avisa', async (saldo) => {
    mockFetch({ ...sessaoViva, 'GET /contas': responde([]) })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovaConta()
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
    await abrirNovaConta()
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
    await abrirNovaConta()
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
    await abrirNovaConta()
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
    await abrirNovaConta()
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
    await abrirNovaConta()
    preencherNovaConta('Banco')
    enviarNovaConta()
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(3))
    const nomes = screen.getAllByRole('listitem').map((item) => item.querySelector('strong')?.textContent)
    // 'Banco' (a0) vem antes de 'banco' (a1) pelo id, e ambos antes de Carteira.
    expect(nomes).toEqual(['Banco', 'banco', 'Carteira'])
  })
})

describe('Conta de benefício', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  function categoria(id: string, nome: string, tipo = 'despesa'): Record<string, unknown> {
    return { id, nome, tipo, paiId: null, cor: null, icone: null }
  }

  const categorias = [
    categoria('cat-ali', 'Alimentação'),
    categoria('cat-mer', 'Mercado'),
    categoria('cat-sal', 'Salário', 'receita'),
  ]

  const vale = (sobra: Partial<Record<string, unknown>> = {}): Record<string, unknown> =>
    conta({ id: 'b1', nome: 'VR', tipo: 'beneficio', saldoCentavos: 80000, categoriasPermitidas: [], ...sobra })

  async function abrirNovoBeneficio(): Promise<HTMLElement> {
    const form = await abrirNovaConta()
    fireEvent.change(screen.getByLabelText('Tipo'), { target: { value: 'beneficio' } })
    return form
  }

  it('só mostra "O que esta conta pode pagar" com o tipo Vale-benefício, só com despesas', async () => {
    mockFetch({ ...sessaoViva, 'GET /contas': responde([]), 'GET /categorias': responde(categorias) })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovaConta()
    expect(screen.queryByText('O que esta conta pode pagar')).toBeNull()
    fireEvent.change(screen.getByLabelText('Tipo'), { target: { value: 'beneficio' } })
    const grupo = screen.getByRole('group', { name: 'O que esta conta pode pagar' })
    expect(within(grupo).getByText(/sem nenhuma marcada, ela aceita qualquer categoria/i)).toBeVisible()
    expect(within(grupo).getByRole('checkbox', { name: 'Alimentação' })).not.toBeChecked()
    expect(within(grupo).getByRole('checkbox', { name: 'Mercado' })).not.toBeChecked()
    expect(within(grupo).queryByRole('checkbox', { name: 'Salário' })).toBeNull()
  })

  it('cria o benefício com as categorias marcadas', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'GET /categorias': responde(categorias),
      'POST /contas': responde(vale({ categoriasPermitidas: ['cat-ali'] }), 201),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovoBeneficio()
    preencherNovaConta('VR', '800,00')
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Alimentação' }))
    enviarNovaConta()
    const lista = await screen.findByRole('list', { name: 'Categorias permitidas em VR' })
    expect(within(lista).getByText('Alimentação')).toBeVisible()
    expect(screen.getByText('Vale-benefício')).toBeVisible()
    expect(JSON.parse(String(chamadasA('POST /contas')[0][1]?.body))).toEqual({
      nome: 'VR',
      tipo: 'beneficio',
      saldoInicialCentavos: 80000,
      categoriasPermitidas: ['cat-ali'],
    })
  })

  it('cria o benefício sem categorias mandando lista vazia', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'GET /categorias': responde(categorias),
      'POST /contas': responde(vale(), 201),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovoBeneficio()
    preencherNovaConta('VR')
    await screen.findByRole('checkbox', { name: 'Alimentação' })
    enviarNovaConta()
    expect(await screen.findByText('Aceita qualquer categoria')).toBeVisible()
    expect(JSON.parse(String(chamadasA('POST /contas')[0][1]?.body))).toEqual({
      nome: 'VR',
      tipo: 'beneficio',
      saldoInicialCentavos: 0,
      categoriasPermitidas: [],
    })
  })

  it('outros tipos não mandam categoriasPermitidas mesmo depois de marcar e trocar de tipo', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'GET /categorias': responde(categorias),
      'POST /contas': responde(conta(), 201),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovoBeneficio()
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Mercado' }))
    fireEvent.change(screen.getByLabelText('Tipo'), { target: { value: 'corrente' } })
    preencherNovaConta('Nubank')
    enviarNovaConta()
    await screen.findByRole('listitem')
    expect(JSON.parse(String(chamadasA('POST /contas')[0][1]?.body))).not.toHaveProperty('categoriasPermitidas')
  })

  it('erro ao carregar as categorias mostra alerta e "Tentar de novo" busca outra vez', async () => {
    // A tela de lançamentos também busca categorias, então a falha vale até o clique em "Tentar de novo".
    let falhando = true
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'GET /categorias': () =>
        Promise.resolve(falhando ? jsonResponse({ erro: 'x' }, 500) : jsonResponse(categorias, 200)),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovoBeneficio()
    const grupo = screen.getByRole('group', { name: 'O que esta conta pode pagar' })
    expect(await within(grupo).findByRole('alert')).toHaveTextContent('Não foi possível carregar as categorias.')
    falhando = false
    fireEvent.click(within(grupo).getByRole('button', { name: 'Tentar de novo' }))
    expect(await within(grupo).findByRole('checkbox', { name: 'Alimentação' })).toBeVisible()
  })

  it('categorias ainda carregando ou com erro não enviam o POST', async () => {
    let falhando = true
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'GET /categorias': () =>
        Promise.resolve(falhando ? jsonResponse({ erro: 'x' }, 500) : jsonResponse(categorias, 200)),
      'POST /contas': responde(vale(), 201),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovoBeneficio()
    preencherNovaConta('VR')
    const grupo = screen.getByRole('group', { name: 'O que esta conta pode pagar' })
    await within(grupo).findByRole('alert')
    enviarNovaConta()
    expect(await screen.findByText(/espere as categorias carregarem/i)).toBeVisible()
    expect(within(grupo).getByRole('button', { name: 'Tentar de novo' })).toBeVisible()
    expect(chamadasA('POST /contas')).toHaveLength(0)
  })

  it('categorias pendentes não enviam o POST', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'GET /categorias': () => new Promise<Response>(() => {}),
      'POST /contas': responde(vale(), 201),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovoBeneficio()
    preencherNovaConta('VR')
    enviarNovaConta()
    expect(await screen.findByText(/espere as categorias carregarem/i)).toBeVisible()
    expect(chamadasA('POST /contas')).toHaveLength(0)
  })

  it('400 do servidor ao criar mostra a mensagem', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([]),
      'GET /categorias': responde(categorias),
      'POST /contas': responde({ erro: 'categoria não encontrada' }, 400),
    })
    render(<App />)
    await screen.findByText('Nenhuma conta ainda.')
    await abrirNovoBeneficio()
    preencherNovaConta('VR')
    enviarNovaConta()
    expect(await screen.findByRole('alert')).toHaveTextContent('categoria não encontrada')
  })

  it('lista o benefício com saldo, selos das categorias e a ação de editar', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([
        vale({ categoriasPermitidas: ['cat-ali', 'cat-mer'] }),
        vale({ id: 'b2', nome: 'VA', categoriasPermitidas: [] }),
        conta(),
      ]),
      'GET /categorias': responde(categorias),
    })
    render(<App />)
    const itens = await screen.findAllByRole('listitem')
    const restrito = itens.find((item) => item.textContent?.includes('VR'))!
    expect(restrito.textContent).toMatch(texto('R$ 800,00'))
    const selos = await within(restrito).findByRole('list', { name: 'Categorias permitidas em VR' })
    expect(within(selos).getAllByRole('listitem').map((selo) => selo.textContent)).toEqual(['Alimentação', 'Mercado'])
    expect(within(restrito).getByRole('button', { name: 'Editar categorias de VR' })).toBeVisible()
    const aberto = itens.find((item) => item.textContent?.includes('VA'))!
    expect(within(aberto).getByText('Aceita qualquer categoria')).toBeVisible()
    const corrente = itens.find((item) => item.textContent?.includes('Nubank'))!
    expect(within(corrente).queryByRole('button', { name: /editar categorias/i })).toBeNull()
  })

  it('editar categorias manda o PUT com as marcadas e atualiza os selos', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([vale({ categoriasPermitidas: ['cat-ali'] })]),
      'GET /categorias': responde(categorias),
      'PUT /contas/b1/categorias-permitidas': responde(vale({ categoriasPermitidas: ['cat-mer'] })),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Editar categorias de VR' }))
    const form = await screen.findByRole('form', { name: 'Categorias de VR' })
    // A lista atual já vem marcada.
    expect(await within(form).findByRole('checkbox', { name: 'Alimentação' })).toBeChecked()
    fireEvent.click(within(form).getByRole('checkbox', { name: 'Alimentação' }))
    fireEvent.click(within(form).getByRole('checkbox', { name: 'Mercado' }))
    fireEvent.submit(form)
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Categorias de VR' })).toBeNull())
    const [url, init] = chamadasA('PUT /contas/b1/categorias-permitidas')[0]
    expect(url).toBe('/contas/b1/categorias-permitidas')
    expect(JSON.parse(String(init?.body))).toEqual({ categoriaIds: ['cat-mer'] })
    const selos = screen.getByRole('list', { name: 'Categorias permitidas em VR' })
    expect(within(selos).getAllByRole('listitem').map((selo) => selo.textContent)).toEqual(['Mercado'])
  })

  it('desmarcar tudo manda lista vazia e a conta passa a aceitar qualquer categoria', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([vale({ categoriasPermitidas: ['cat-ali'] })]),
      'GET /categorias': responde(categorias),
      'PUT /contas/b1/categorias-permitidas': responde(vale()),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Editar categorias de VR' }))
    const form = await screen.findByRole('form', { name: 'Categorias de VR' })
    fireEvent.click(await within(form).findByRole('checkbox', { name: 'Alimentação' }))
    fireEvent.submit(form)
    expect(await screen.findByText('Aceita qualquer categoria')).toBeVisible()
    expect(JSON.parse(String(chamadasA('PUT /contas/b1/categorias-permitidas')[0][1]?.body))).toEqual({
      categoriaIds: [],
    })
  })

  it('400 ao editar mostra a mensagem e mantém o painel aberto', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([vale()]),
      'GET /categorias': responde(categorias),
      'PUT /contas/b1/categorias-permitidas': responde({ erro: 'categoria não encontrada' }, 400),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Editar categorias de VR' }))
    const form = await screen.findByRole('form', { name: 'Categorias de VR' })
    await within(form).findByRole('checkbox', { name: 'Alimentação' })
    fireEvent.submit(form)
    expect(await within(form).findByRole('alert')).toHaveTextContent('categoria não encontrada')
  })

  it('404 ao editar avisa que a conta não existe mais e recarrega', async () => {
    let chamadas = 0
    mockFetch({
      ...sessaoViva,
      'GET /contas': () => {
        chamadas += 1
        return Promise.resolve(jsonResponse(chamadas === 1 ? [vale()] : [], 200))
      },
      'GET /categorias': responde(categorias),
      'PUT /contas/b1/categorias-permitidas': responde({ erro: 'não encontrado' }, 404),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Editar categorias de VR' }))
    const form = await screen.findByRole('form', { name: 'Categorias de VR' })
    await within(form).findByRole('checkbox', { name: 'Alimentação' })
    fireEvent.submit(form)
    expect(await screen.findByText('Nenhuma conta ainda.')).toBeVisible()
    expect(screen.getByRole('alert')).toHaveTextContent('Essa conta não existe mais.')
  })

  it('401 ao editar volta para o login', async () => {
    let sessao = true
    mockFetch({
      'GET /auth/eu': () => Promise.resolve(sessao ? jsonResponse(usuario, 200) : jsonResponse({ erro: 'x' }, 401)),
      'GET /contas': responde([vale()]),
      'GET /categorias': responde(categorias),
      'PUT /contas/b1/categorias-permitidas': () => {
        sessao = false
        return Promise.resolve(jsonResponse({ erro: 'x' }, 401))
      },
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: 'Editar categorias de VR' }))
    const form = await screen.findByRole('form', { name: 'Categorias de VR' })
    await within(form).findByRole('checkbox', { name: 'Alimentação' })
    fireEvent.submit(form)
    expect(await screen.findByRole('form', { name: 'Entrar' })).toBeVisible()
  })

  it('edita nome e saldo inicial e mostra a conta nova na lista', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([conta()]),
      'PATCH /contas/c1': responde(conta({ nome: 'Nubank PJ', saldoInicialCentavos: 50000, saldoCentavos: 50000 })),
    })
    render(<App />)
    await contasNubank()
    fireEvent.click(within(secaoContas()).getByRole('button', { name: 'Editar Nubank' }))
    const form = await screen.findByRole('form', { name: 'Editar Nubank' })
    expect(within(form).getByLabelText('Saldo inicial em reais')).toHaveValue('1.234,56')
    fireEvent.change(within(form).getByLabelText('Nome'), { target: { value: 'Nubank PJ' } })
    fireEvent.change(within(form).getByLabelText('Saldo inicial em reais'), { target: { value: '500,00' } })
    fireEvent.submit(form)

    expect(await within(secaoContas()).findByText('Nubank PJ')).toBeVisible()
    expect(JSON.parse(String(chamadasA('PATCH /contas/c1')[0][1]?.body))).toEqual({
      nome: 'Nubank PJ',
      saldoInicialCentavos: 50000,
    })
  })

  it('edita limite e conta de pagamento do cartão, com a fatura inicial como positivo', async () => {
    const corrente = conta()
    const poupanca = conta({ id: 'c2', nome: 'Poupança', tipo: 'poupanca' })
    const cartao = conta({
      id: 'k1',
      nome: 'Cartão',
      tipo: 'cartao_credito',
      saldoInicialCentavos: -20000,
      saldoCentavos: -20000,
      cartao: { limiteCentavos: 100000, diaFechamento: 10, diaVencimento: 20, contaPagamentoId: 'c1', disponivelCentavos: 80000 },
    })
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([cartao, corrente, poupanca]),
      'PATCH /contas/k1': responde(cartao),
    })
    render(<App />)
    await contasNubank()
    fireEvent.click(within(secaoContas()).getByRole('button', { name: 'Editar Cartão' }))
    const form = await screen.findByRole('form', { name: 'Editar Cartão' })
    expect(within(form).getByLabelText('Fatura em aberto quando o cartão entrou no app')).toHaveValue('200,00')
    fireEvent.change(within(form).getByLabelText('Limite em reais'), { target: { value: '2.500,00' } })
    fireEvent.change(within(form).getByLabelText('Conta de pagamento'), { target: { value: 'c2' } })
    fireEvent.submit(form)

    await waitFor(() => expect(chamadasA('PATCH /contas/k1')).toHaveLength(1))
    expect(JSON.parse(String(chamadasA('PATCH /contas/k1')[0][1]?.body))).toEqual({
      nome: 'Cartão',
      saldoInicialCentavos: -20000,
      cartao: { limiteCentavos: 250000, contaPagamentoId: 'c2' },
    })
  })

  it('mostra o 409 do nome repetido na edição', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([conta()]),
      'PATCH /contas/c1': responde({ erro: 'nome repetido' }, 409),
    })
    render(<App />)
    await contasNubank()
    fireEvent.click(within(secaoContas()).getByRole('button', { name: 'Editar Nubank' }))
    const form = await screen.findByRole('form', { name: 'Editar Nubank' })
    fireEvent.submit(form)
    expect(await within(form).findByRole('alert')).toHaveTextContent('Já existe uma conta com esse nome.')
  })

  it('apaga a conta só depois de mostrar o resumo e digitar o nome', async () => {
    let apagada = false
    mockFetch({
      ...sessaoViva,
      // Depois do DELETE a tela busca as contas de novo, e o servidor já não tem a conta.
      'GET /contas': () => Promise.resolve(jsonResponse(apagada ? [] : [conta()], 200)),
      'GET /contas/c1/resumo-exclusao': responde({ lancamentos: 12, transferencias: 2, recorrencias: 1, movimentosCripto: 0, titulosRendaFixa: 0 }),
      'DELETE /contas/c1': () => {
        apagada = true
        return Promise.resolve(new Response(null, { status: 204 }))
      },
    })
    render(<App />)
    await contasNubank()
    fireEvent.click(within(secaoContas()).getByRole('button', { name: 'Apagar Nubank' }))
    const form = await screen.findByRole('form', { name: 'Apagar Nubank' })
    expect(await within(form).findByText('12 lançamentos')).toBeVisible()
    expect(within(form).getByText('1 recorrência')).toBeVisible()
    expect(within(form).getByText(/2 transferências com outras contas viram lançamento comum lá/)).toBeVisible()
    const botao = within(form).getByRole('button', { name: 'Apagar de vez' })
    expect(botao).toBeDisabled()
    fireEvent.change(within(form).getByLabelText('Digite o nome da conta para confirmar'), { target: { value: 'Nuban' } })
    expect(botao).toBeDisabled()
    fireEvent.change(within(form).getByLabelText('Digite o nome da conta para confirmar'), { target: { value: 'Nubank' } })
    fireEvent.click(botao)

    await waitFor(() => expect(chamadasA('DELETE /contas/c1')).toHaveLength(1))
    expect(await screen.findByText('Nenhuma conta ainda.')).toBeVisible()
  })

  it('mostra a recusa de apagar a conta que paga um cartão', async () => {
    mockFetch({
      ...sessaoViva,
      'GET /contas': responde([conta()]),
      'GET /contas/c1/resumo-exclusao': responde({ lancamentos: 0, transferencias: 0, recorrencias: 0, movimentosCripto: 0, titulosRendaFixa: 0 }),
      'DELETE /contas/c1': responde({ erro: 'esta conta paga um cartão; troque a conta de pagamento antes' }, 400),
    })
    render(<App />)
    await contasNubank()
    fireEvent.click(within(secaoContas()).getByRole('button', { name: 'Apagar Nubank' }))
    const form = await screen.findByRole('form', { name: 'Apagar Nubank' })
    await within(form).findByText('0 lançamentos')
    fireEvent.change(within(form).getByLabelText('Digite o nome da conta para confirmar'), { target: { value: 'Nubank' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Apagar de vez' }))
    expect(await within(form).findByRole('alert')).toHaveTextContent('esta conta paga um cartão')
  })
})
