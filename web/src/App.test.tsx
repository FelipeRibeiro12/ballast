import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import App from './App'

type Rota = (corpo: unknown) => Promise<Response>
type Rotas = Partial<Record<string, Rota>>

const usuario = { id: '7f1e2c9a-0b6d-4f43-9a52-1c8d3e5b6a70', email: 'ana@exemplo.com', nome: 'Ana' }

function jsonResponse(corpo: unknown, status: number, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(corpo), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

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

// Roteia por "MÉTODO caminho". Recebe funções para a promessa rejeitada só nascer
// quando o fetch for chamado. /healthz e GET /contas respondem 200 salvo se a rota for informada.
function mockFetch(rotas: Rotas): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const rota = rotas[chave]
      if (rota) return rota(init?.body)
      if (chave === 'GET /healthz') return Promise.resolve(jsonResponse({ mensagem: 'ok' }, 200))
      // A tela logada sempre busca as contas; os testes de sessão não querem saber delas.
      if (chave === 'GET /contas') return Promise.resolve(jsonResponse([], 200))
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

const semResposta = (): Promise<Response> => new Promise<Response>(() => {})

function chamadasA(chave: string): [string, RequestInit | undefined][] {
  return vi
    .mocked(fetch)
    .mock.calls.map(([url, init]): [string, RequestInit | undefined] => [String(url), init])
    .filter(([url, init]) => `${init?.method ?? 'GET'} ${url}` === chave)
}

function preencherEEnviar(email: string, senha: string): void {
  fireEvent.change(screen.getByLabelText(/e-mail/i), { target: { value: email } })
  fireEvent.change(screen.getByLabelText(/senha/i), { target: { value: senha } })
  fireEvent.click(screen.getByRole('button', { name: /entrar/i }))
}

describe('App: saúde da API', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  const anonimo = { 'GET /auth/eu': () => Promise.resolve(jsonResponse({ erro: 'x' }, 401)) }

  it('mostra o título Ballast e o estado de carregando', () => {
    mockFetch({ 'GET /auth/eu': semResposta, 'GET /healthz': semResposta })
    render(<App />)
    expect(screen.getByRole('heading', { name: /ballast/i })).toBeVisible()
    expect(screen.getByText(/carregando/i)).toBeVisible()
  })

  it('com a API bem não mostra nada do /healthz na tela', async () => {
    mockFetch(anonimo)
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    await waitFor(() => expect(fetch).toHaveBeenCalledWith('/healthz'))
    expect(screen.queryByText('ok')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('mostra erro genérico quando a API responde 503', async () => {
    mockFetch({ ...anonimo, 'GET /healthz': () => Promise.resolve(jsonResponse({ erro: 'indisponível' }, 503)) })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível/i)
  })

  it('mostra erro genérico quando a rede está fora', async () => {
    mockFetch({ ...anonimo, 'GET /healthz': () => Promise.reject(new TypeError('Failed to fetch')) })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível/i)
  })

  it('trata resposta 200 fora do formato como erro', async () => {
    mockFetch({ ...anonimo, 'GET /healthz': () => Promise.resolve(jsonResponse({ outra: 1 }, 200)) })
    render(<App />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível/i)
  })
})

describe('App: sessão', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('abre com 401 em /auth/eu e mostra o formulário de login', async () => {
    mockFetch({ 'GET /auth/eu': () => Promise.resolve(jsonResponse({ erro: 'x' }, 401)) })
    render(<App />)
    expect(await screen.findByLabelText(/e-mail/i)).toBeVisible()
    expect(screen.getByLabelText(/senha/i)).toBeVisible()
    expect(chamadasA('GET /auth/eu')[0][1]).toMatchObject({ credentials: 'same-origin' })
  })

  it('abre com 200 em /auth/eu e mostra quem está logado', async () => {
    mockFetch({ 'GET /auth/eu': () => Promise.resolve(jsonResponse(usuario, 200)) })
    render(<App />)
    expect(await screen.findByText(/ana@exemplo\.com/)).toBeVisible()
    expect(screen.getByRole('button', { name: /sair/i })).toBeVisible()
    expect(screen.queryByLabelText(/senha/i)).toBeNull()
  })

  it('abre no Início com o saldo projetado, o resumo das contas e a navegação por seções', async () => {
    window.location.hash = ''
    mockFetch({ 'GET /auth/eu': () => Promise.resolve(jsonResponse(usuario, 200)) })
    render(<App />)
    expect(await screen.findByRole('heading', { level: 2, name: 'Início' })).toBeVisible()
    expect(await screen.findByRole('heading', { name: 'Saldo projetado' })).toBeVisible()
    expect(screen.getByRole('region', { name: 'Resumo das contas' })).toBeVisible()
    const navegacao = screen.getByRole('navigation', { name: 'Seções' })
    expect(within(navegacao).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Início',
      'Lançamentos',
      'Contas',
      'Recorrências',
      'Cripto',
    ])
    expect(within(navegacao).getByRole('link', { name: 'Início' })).toHaveAttribute('aria-current', 'page')
    expect(chamadasA('GET /saldo-projetado?dias=90').length).toBeGreaterThan(0)
  })

  it('cada link da navegação mostra só a sua seção e o hash acompanha', async () => {
    window.location.hash = ''
    mockFetch({ 'GET /auth/eu': () => Promise.resolve(jsonResponse(usuario, 200)) })
    render(<App />)
    await screen.findByRole('heading', { level: 2, name: 'Início' })
    const navegacao = screen.getByRole('navigation', { name: 'Seções' })

    fireEvent.click(within(navegacao).getByRole('link', { name: 'Contas' }))
    expect(await screen.findByRole('region', { name: 'Contas' })).toBeVisible()
    expect(window.location.hash).toBe('#/contas')
    expect(screen.queryByRole('heading', { name: 'Saldo projetado' })).toBeNull()
    expect(screen.queryByRole('region', { name: 'Lançamentos' })).toBeNull()
    expect(within(navegacao).getByRole('link', { name: 'Contas' })).toHaveAttribute('aria-current', 'page')

    fireEvent.click(within(navegacao).getByRole('link', { name: 'Recorrências' }))
    expect(await screen.findByRole('region', { name: 'Recorrências' })).toBeVisible()
    expect(screen.queryByRole('region', { name: 'Contas' })).toBeNull()

    fireEvent.click(within(navegacao).getByRole('link', { name: 'Lançamentos' }))
    expect(await screen.findByRole('region', { name: 'Lançamentos' })).toBeVisible()
  })

  it('a conta cripto fica fora dos selects de lançamento e aparece só na aba Cripto', async () => {
    const base = {
      moeda: 'BRL',
      saldoInicialCentavos: 0,
      saldoCentavos: 0,
      arquivada: false,
      criadaEm: '2026-10-19T12:00:00Z',
      cartao: null,
      categoriasPermitidas: null,
    }
    const corrente = { ...base, id: '1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed', nome: 'Nubank', tipo: 'corrente' }
    const exchange = { ...base, id: '2c9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bee', nome: 'Exchange', tipo: 'cripto' }
    window.location.hash = '#/lancamentos'
    mockFetch({
      'GET /auth/eu': () => Promise.resolve(jsonResponse(usuario, 200)),
      'GET /contas': () => Promise.resolve(jsonResponse([exchange, corrente], 200)),
      'GET /ativos-cripto': () => Promise.resolve(jsonResponse([], 200)),
      'GET /movimentos-cripto': () => Promise.resolve(jsonResponse([], 200)),
    })
    render(<App />)
    const abrir = await screen.findByRole('button', { name: 'Novo' })
    await waitFor(() => expect(abrir).toBeEnabled())
    fireEvent.click(abrir)
    const formulario = await screen.findByRole('form', { name: 'Novo lançamento' })
    const opcoes = within(within(formulario).getByLabelText('Conta')).getAllByRole('option')
    expect(opcoes.map((opcao) => opcao.textContent)).toEqual(['Nubank'])

    fireEvent.click(within(screen.getByRole('navigation', { name: 'Seções' })).getByRole('link', { name: 'Cripto' }))
    expect(await screen.findByRole('region', { name: 'Cripto' })).toBeVisible()
    expect(await screen.findByText('Nenhum ativo cadastrado.')).toBeVisible()
  })

  it('um hash desconhecido abre o Início', async () => {
    window.location.hash = '#/nao-existe'
    mockFetch({ 'GET /auth/eu': () => Promise.resolve(jsonResponse(usuario, 200)) })
    render(<App />)
    expect(await screen.findByRole('heading', { level: 2, name: 'Início' })).toBeVisible()
  })

  it('mostra o e-mail quando o nome é nulo', async () => {
    mockFetch({ 'GET /auth/eu': () => Promise.resolve(jsonResponse({ ...usuario, nome: null }, 200)) })
    render(<App />)
    expect(await screen.findByText(/ana@exemplo\.com/)).toBeVisible()
  })

  it('mostra alerta com nova tentativa quando /auth/eu dá 500', async () => {
    let chamadas = 0
    mockFetch({
      'GET /auth/eu': () => {
        chamadas += 1
        return Promise.resolve(chamadas === 1 ? jsonResponse({ erro: 'boom' }, 500) : jsonResponse(usuario, 200))
      },
    })
    render(<App />)
    const alerta = await screen.findByRole('alert')
    expect(alerta).not.toHaveTextContent(/boom/)
    fireEvent.click(screen.getByRole('button', { name: /tentar de novo/i }))
    expect(await screen.findByText(/ana@exemplo\.com/)).toBeVisible()
  })

  it('mostra alerta quando a rede falha em /auth/eu', async () => {
    mockFetch({ 'GET /auth/eu': () => Promise.reject(new TypeError('Failed to fetch')) })
    render(<App />)
    expect(await screen.findByRole('alert')).toBeVisible()
    expect(screen.getByRole('button', { name: /tentar de novo/i })).toBeVisible()
  })

  it('trata /auth/eu 200 com formato inesperado como erro genérico', async () => {
    mockFetch({ 'GET /auth/eu': () => Promise.resolve(jsonResponse({ id: 1 }, 200)) })
    render(<App />)
    expect(await screen.findByRole('alert')).toBeVisible()
    expect(screen.queryByLabelText(/senha/i)).toBeNull()
  })
})

describe('App: login', () => {
  const eu401 = () => Promise.resolve(jsonResponse({ erro: 'x' }, 401))

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('declara os atributos do formulário', async () => {
    mockFetch({ 'GET /auth/eu': eu401 })
    render(<App />)
    const email = await screen.findByLabelText(/e-mail/i)
    const senha = screen.getByLabelText(/senha/i)
    expect(email).toHaveAttribute('type', 'email')
    expect(email).toHaveAttribute('autocomplete', 'username')
    expect(email).toHaveAttribute('maxlength', '254')
    expect(senha).toHaveAttribute('type', 'password')
    expect(senha).toHaveAttribute('autocomplete', 'current-password')
    expect(senha).toHaveAttribute('maxlength', '128')
    expect(senha).toBeRequired()
  })

  it('com sucesso envia JSON e mostra a tela logada', async () => {
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.resolve(jsonResponse(usuario, 200)),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'senha-correta')
    expect(await screen.findByText(/ana@exemplo\.com/)).toBeVisible()
    const [url, init] = chamadasA('POST /auth/login')[0]
    expect(url).toBe('/auth/login')
    expect(init).toMatchObject({ credentials: 'same-origin', headers: { 'Content-Type': 'application/json' } })
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'ana@exemplo.com', senha: 'senha-correta' })
  })

  it('401 mostra uma mensagem única e limpa a senha', async () => {
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.resolve(jsonResponse({ erro: 'senha incorreta para ana' }, 401)),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'errada')
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('E-mail ou senha incorretos.')
    expect(alerta).not.toHaveTextContent(/senha incorreta para/)
    expect(screen.getByLabelText(/senha/i)).toHaveValue('')
    expect(screen.getByLabelText(/e-mail/i)).toHaveValue('ana@exemplo.com')
  })

  it('429 com Retry-After mostra o número de segundos', async () => {
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.resolve(jsonResponse({ erro: 'x' }, 429, { 'Retry-After': '8' })),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'x')
    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Tente de novo em 8 segundos.')
  })

  it('429 com Retry-After 1 usa o singular', async () => {
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.resolve(jsonResponse({ erro: 'x' }, 429, { 'Retry-After': '1' })),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'x')
    expect(await screen.findByRole('alert')).toHaveTextContent('Tente de novo em 1 segundo.')
  })

  it.each([[null], ['abc'], ['0'], ['-3'], ['1.5']])('429 com Retry-After %s mostra mensagem sem número', async (valor) => {
    const headers: Record<string, string> = valor === null ? {} : { 'Retry-After': valor }
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.resolve(jsonResponse({ erro: 'x' }, 429, headers)),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'x')
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Muitas tentativas. Tente de novo em instantes.')
    expect(alerta.textContent).not.toMatch(/\d/)
  })

  it.each([[400], [413], [415], [500]])('%i mostra mensagem genérica sem o texto do servidor', async (status) => {
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.resolve(jsonResponse({ erro: 'pq: syntax error at line 3' }, status)),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'x')
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Não foi possível entrar. Tente de novo.')
    expect(alerta).not.toHaveTextContent(/pq:/)
  })

  it('erro de rede mostra mensagem genérica', async () => {
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.reject(new TypeError('Failed to fetch')),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'x')
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível entrar. Tente de novo.')
  })

  it('200 com formato inesperado vira erro genérico e não loga', async () => {
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.resolve(jsonResponse({ email: 1 }, 200)),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'x')
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível entrar. Tente de novo.')
    expect(screen.queryByRole('button', { name: /sair/i })).toBeNull()
  })

  it('desabilita o botão durante o envio e não envia duas vezes', async () => {
    let responder: (r: Response) => void = () => {}
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => new Promise<Response>((resolve) => { responder = resolve }),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'x')
    const botao = await screen.findByRole('button', { name: /entrando/i })
    expect(botao).toBeDisabled()
    fireEvent.submit(screen.getByRole('form', { name: /entrar/i }))
    expect(chamadasA('POST /auth/login')).toHaveLength(1)
    responder(jsonResponse(usuario, 200))
    expect(await screen.findByText(/ana@exemplo\.com/)).toBeVisible()
  })

  it('reabilita o botão depois de uma falha', async () => {
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.resolve(jsonResponse({ erro: 'x' }, 401)),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'x')
    await screen.findByRole('alert')
    expect(screen.getByRole('button', { name: /entrar/i })).toBeEnabled()
  })

  it('a senha não aparece em nenhum console.*', async () => {
    const espioes = (['log', 'info', 'warn', 'error', 'debug'] as const).map((nivel) =>
      vi.spyOn(console, nivel).mockImplementation(() => {}),
    )
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.reject(new TypeError('Failed to fetch')),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'segredo-super-unico')
    await screen.findByRole('alert')
    for (const espiao of espioes) {
      expect(JSON.stringify(espiao.mock.calls)).not.toContain('segredo-super-unico')
    }
  })

  it('não guarda nada em localStorage nem sessionStorage', async () => {
    mockFetch({
      'GET /auth/eu': eu401,
      'POST /auth/login': () => Promise.resolve(jsonResponse(usuario, 200)),
    })
    render(<App />)
    await screen.findByLabelText(/e-mail/i)
    preencherEEnviar('ana@exemplo.com', 'segredo-super-unico')
    await screen.findByText(/ana@exemplo\.com/)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})

describe('App: logout', () => {
  const eu200 = () => Promise.resolve(jsonResponse(usuario, 200))

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('204 volta ao formulário de login', async () => {
    mockFetch({
      'GET /auth/eu': eu200,
      'POST /auth/logout': () => Promise.resolve(new Response(null, { status: 204 })),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /sair/i }))
    expect(await screen.findByLabelText(/e-mail/i)).toBeVisible()
    const [url, init] = chamadasA('POST /auth/logout')[0]
    expect(url).toBe('/auth/logout')
    expect(init).toMatchObject({ method: 'POST', credentials: 'same-origin' })
    expect(init?.body).toBeUndefined()
  })

  it('falha de rede mantém logado e avisa', async () => {
    mockFetch({
      'GET /auth/eu': eu200,
      'POST /auth/logout': () => Promise.reject(new TypeError('Failed to fetch')),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /sair/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível sair/i)
    expect(screen.getByText(/ana@exemplo\.com/)).toBeVisible()
    expect(screen.getByRole('button', { name: /sair/i })).toBeEnabled()
  })

  it('500 mantém logado e avisa', async () => {
    mockFetch({
      'GET /auth/eu': eu200,
      'POST /auth/logout': () => Promise.resolve(jsonResponse({ erro: 'x' }, 500)),
    })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /sair/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/não foi possível sair/i)
    await waitFor(() => expect(screen.getByRole('button', { name: /sair/i })).toBeEnabled())
  })
})

describe('App: cadastro', () => {
  const eu401 = () => Promise.resolve(jsonResponse({ erro: 'x' }, 401))
  const novoUsuario = { ...usuario, nome: null }

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
  })

  async function abrirCadastro(rotas: Rotas): Promise<void> {
    vi.stubEnv('VITE_REGISTRO_ABERTO', 'true')
    mockFetch({ 'GET /auth/eu': eu401, ...rotas })
    render(<App />)
    fireEvent.click(await screen.findByRole('button', { name: /criar conta/i }))
    await screen.findByRole('form', { name: /criar conta/i })
  }

  function preencherCadastro(email: string, senha: string, confirmacao: string = senha): void {
    fireEvent.change(screen.getByLabelText(/e-mail/i), { target: { value: email } })
    fireEvent.change(screen.getByLabelText(/^senha/i), { target: { value: senha } })
    fireEvent.change(screen.getByLabelText(/confirmar senha/i), { target: { value: confirmacao } })
    fireEvent.submit(screen.getByRole('form', { name: /criar conta/i }))
  }

  it.each([[undefined], [''], ['false'], ['TRUE'], ['1']])(
    'com a flag %j não existe "Criar conta"',
    async (valor) => {
      if (valor !== undefined) vi.stubEnv('VITE_REGISTRO_ABERTO', valor)
      mockFetch({ 'GET /auth/eu': eu401 })
      render(<App />)
      await screen.findByLabelText(/e-mail/i)
      expect(screen.queryByRole('button', { name: /criar conta/i })).toBeNull()
    },
  )

  it('declara os atributos do formulário e volta com "Já tenho conta"', async () => {
    await abrirCadastro({})
    const nome = screen.getByLabelText(/nome/i)
    const email = screen.getByLabelText(/e-mail/i)
    const senha = screen.getByLabelText(/^senha/i)
    const confirmacao = screen.getByLabelText(/confirmar senha/i)
    expect(nome).toHaveAttribute('maxlength', '100')
    expect(nome).not.toBeRequired()
    expect(email).toHaveAttribute('type', 'email')
    expect(email).toHaveAttribute('autocomplete', 'username')
    expect(email).toHaveAttribute('maxlength', '254')
    expect(senha).toHaveAttribute('autocomplete', 'new-password')
    expect(senha).toHaveAttribute('minlength', '12')
    expect(senha).toHaveAttribute('maxlength', '128')
    expect(confirmacao).toHaveAttribute('autocomplete', 'new-password')
    fireEvent.click(screen.getByRole('button', { name: /já tenho conta/i }))
    expect(await screen.findByRole('form', { name: /entrar/i })).toBeVisible()
  })

  it('cadastra, faz o login automático e mostra a tela logada', async () => {
    await abrirCadastro({
      'POST /auth/cadastro': () => Promise.resolve(jsonResponse(novoUsuario, 201)),
      'POST /auth/login': () => Promise.resolve(jsonResponse(novoUsuario, 200)),
    })
    fireEvent.change(screen.getByLabelText(/nome/i), { target: { value: 'Ana' } })
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1')
    expect(await screen.findByRole('button', { name: /sair/i })).toBeVisible()
    expect(JSON.parse(String(chamadasA('POST /auth/cadastro')[0][1]?.body))).toEqual({
      email: 'ana@exemplo.com',
      senha: 'senha-bem-longa-1',
      nome: 'Ana',
    })
    expect(JSON.parse(String(chamadasA('POST /auth/login')[0][1]?.body))).toEqual({
      email: 'ana@exemplo.com',
      senha: 'senha-bem-longa-1',
    })
  })

  it('não manda o campo nome quando ele está vazio', async () => {
    await abrirCadastro({
      'POST /auth/cadastro': () => Promise.resolve(jsonResponse(novoUsuario, 201)),
      'POST /auth/login': () => Promise.resolve(jsonResponse(novoUsuario, 200)),
    })
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1')
    await screen.findByRole('button', { name: /sair/i })
    expect(JSON.parse(String(chamadasA('POST /auth/cadastro')[0][1]?.body))).not.toHaveProperty('nome')
  })

  it('senhas diferentes não enviam nada e avisam', async () => {
    await abrirCadastro({})
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1', 'senha-bem-longa-2')
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('As senhas não conferem.')
    expect(chamadasA('POST /auth/cadastro')).toHaveLength(0)
    expect(screen.getByLabelText(/^senha/i)).toHaveValue('')
    expect(screen.getByLabelText(/confirmar senha/i)).toHaveValue('')
    expect(screen.getByLabelText(/^senha/i)).toHaveAccessibleDescription('As senhas não conferem.')
  })

  it('409 mostra que o e-mail já tem conta e limpa as senhas', async () => {
    await abrirCadastro({
      'POST /auth/cadastro': () => Promise.resolve(jsonResponse({ erro: 'e-mail já cadastrado' }, 409)),
    })
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1')
    expect(await screen.findByRole('alert')).toHaveTextContent('Este e-mail já tem conta.')
    expect(screen.getByLabelText(/^senha/i)).toHaveValue('')
    expect(screen.getByLabelText(/confirmar senha/i)).toHaveValue('')
    expect(screen.getByLabelText(/e-mail/i)).toHaveValue('ana@exemplo.com')
    expect(screen.getByRole('button', { name: /criar conta/i })).toBeEnabled()
  })

  it('400 mostra a mensagem do servidor', async () => {
    await abrirCadastro({
      'POST /auth/cadastro': () => Promise.resolve(jsonResponse({ erro: 'senha fraca demais' }, 400)),
    })
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1')
    expect(await screen.findByRole('alert')).toHaveTextContent('senha fraca demais')
  })

  it('400 sem mensagem no formato do contrato cai no texto genérico', async () => {
    await abrirCadastro({
      'POST /auth/cadastro': () => Promise.resolve(jsonResponse({ outra: 1 }, 400)),
    })
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1')
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível criar a conta. Tente de novo.')
  })

  it.each([[401], [404], [413], [415], [500]])('%i mostra mensagem genérica', async (status) => {
    await abrirCadastro({
      'POST /auth/cadastro': () => Promise.resolve(jsonResponse({ erro: 'detalhe interno' }, status)),
    })
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1')
    const alerta = await screen.findByRole('alert')
    expect(alerta).toHaveTextContent('Não foi possível criar a conta. Tente de novo.')
    expect(alerta).not.toHaveTextContent(/detalhe interno/)
  })

  it('erro de rede mostra mensagem genérica', async () => {
    await abrirCadastro({ 'POST /auth/cadastro': () => Promise.reject(new TypeError('Failed to fetch')) })
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1')
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível criar a conta. Tente de novo.')
  })

  it('login pós-cadastro falhando volta para o login com o aviso', async () => {
    await abrirCadastro({
      'POST /auth/cadastro': () => Promise.resolve(jsonResponse(novoUsuario, 201)),
      'POST /auth/login': () => Promise.resolve(jsonResponse({ erro: 'x' }, 500)),
    })
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1')
    expect(await screen.findByRole('form', { name: /entrar/i })).toBeVisible()
    expect(screen.getByRole('status')).toHaveTextContent('Conta criada. Entre com seu e-mail e senha.')
    expect(screen.queryByRole('button', { name: /sair/i })).toBeNull()
  })

  it('desabilita o botão durante o envio e não envia duas vezes', async () => {
    let responder: (r: Response) => void = () => {}
    await abrirCadastro({
      'POST /auth/cadastro': () => new Promise<Response>((resolve) => { responder = resolve }),
      'POST /auth/login': () => Promise.resolve(jsonResponse(novoUsuario, 200)),
    })
    preencherCadastro('ana@exemplo.com', 'senha-bem-longa-1')
    expect(await screen.findByRole('button', { name: /criando/i })).toBeDisabled()
    fireEvent.submit(screen.getByRole('form', { name: /criar conta/i }))
    expect(chamadasA('POST /auth/cadastro')).toHaveLength(1)
    responder(jsonResponse(novoUsuario, 201))
    expect(await screen.findByRole('button', { name: /sair/i })).toBeVisible()
    expect(chamadasA('POST /auth/login')).toHaveLength(1)
  })

  it('a senha não aparece em nenhum console.*', async () => {
    const espioes = (['log', 'info', 'warn', 'error', 'debug'] as const).map((nivel) =>
      vi.spyOn(console, nivel).mockImplementation(() => {}),
    )
    await abrirCadastro({ 'POST /auth/cadastro': () => Promise.reject(new TypeError('Failed to fetch')) })
    preencherCadastro('ana@exemplo.com', 'segredo-super-unico')
    await screen.findByRole('alert')
    for (const espiao of espioes) {
      expect(JSON.stringify(espiao.mock.calls)).not.toContain('segredo-super-unico')
    }
  })
})
