import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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

// Roteia por "MÉTODO caminho". Recebe funções para a promessa rejeitada só nascer
// quando o fetch for chamado. /healthz responde 200 salvo se a rota for informada.
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

  it('mostra a mensagem lida do banco quando a API responde 200', async () => {
    mockFetch(anonimo)
    render(<App />)
    expect(await screen.findByText('ok')).toBeVisible()
    expect(fetch).toHaveBeenCalledWith('/healthz')
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

  it.each([[400], [415], [500]])('%i mostra mensagem genérica sem o texto do servidor', async (status) => {
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
