import { fireEvent, render, screen, within } from '@testing-library/react'
import Seguranca from './Seguranca'

function jsonResponse(corpo: unknown, status: number, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json', ...headers } })
}

type Rotas = Record<string, (corpo: unknown) => Promise<Response>>

const responde =
  (corpo: unknown, status = 200, headers: Record<string, string> = {}) =>
  () =>
    Promise.resolve(jsonResponse(corpo, status, headers))

const desligado = { ativo: false, codigosRestantes: 0 }
const ligado = { ativo: true, codigosRestantes: 7 }
const configuracao = {
  segredo: 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ',
  uri: 'otpauth://totp/Ballast:ana%40exemplo.com?issuer=Ballast',
}
const codigos = ['abcde-fghjk', 'mnpqr-stuvw', 'xyz23-45678']

function mockFetch(rotas: Rotas): void {
  vi.stubGlobal(
    'fetch',
    vi.fn((url: string, init?: RequestInit) => {
      const chave = `${init?.method ?? 'GET'} ${url}`
      const corpo: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
      const rota = rotas[chave]
      if (rota) return rota(corpo)
      return Promise.reject(new Error(`rota sem mock: ${chave}`))
    }),
  )
}

function corpoDe(chave: string): unknown {
  const chamada = vi.mocked(fetch).mock.calls.find(([url, init]) => `${init?.method ?? 'GET'} ${String(url)}` === chave)
  const corpo = chamada?.[1]?.body
  return typeof corpo === 'string' ? JSON.parse(corpo) : undefined
}

function renderizar() {
  const aoSessaoExpirar = vi.fn()
  render(<Seguranca aoSessaoExpirar={aoSessaoExpirar} />)
  return { aoSessaoExpirar }
}

function preencher(form: HTMLElement, rotulo: string, valor: string): void {
  fireEvent.change(within(form).getByLabelText(rotulo), { target: { value: valor } })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('Segurança', () => {
  it('desligado, explica e oferece ligar', async () => {
    mockFetch({ 'GET /auth/mfa': responde(desligado) })
    renderizar()

    expect(await screen.findByText('Verificação em duas etapas desligada.')).toBeVisible()
    expect(screen.getByRole('heading', { level: 2, name: 'Segurança' })).toBeVisible()
    expect(screen.getByRole('button', { name: 'Ligar' })).toBeVisible()
  })

  it('liga do começo ao fim: senha, QR, código e códigos de recuperação', async () => {
    let status = desligado
    mockFetch({
      'GET /auth/mfa': () => Promise.resolve(jsonResponse(status, 200)),
      'POST /auth/mfa/configurar': responde(configuracao),
      'POST /auth/mfa/ativar': () => {
        status = { ativo: true, codigosRestantes: 3 }
        return Promise.resolve(jsonResponse({ codigosDeRecuperacao: codigos }, 200))
      },
    })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Ligar' }))

    const senha = screen.getByRole('form', { name: 'Confirmar senha' })
    preencher(senha, 'Senha', 'minha-senha-1234')
    fireEvent.click(within(senha).getByRole('button', { name: 'Continuar' }))

    expect(await screen.findByRole('img', { name: 'QR code para o app autenticador' })).toBeVisible()
    expect(screen.getByText('GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ')).toBeVisible()
    expect(corpoDe('POST /auth/mfa/configurar')).toEqual({ senha: 'minha-senha-1234' })

    const ativar = screen.getByRole('form', { name: 'Ativar verificação' })
    preencher(ativar, 'Código do app', '123456')
    fireEvent.click(within(ativar).getByRole('button', { name: 'Ativar' }))

    const lista = await screen.findByRole('list', { name: 'Códigos de recuperação' })
    expect(within(lista).getAllByRole('listitem').map((item) => item.textContent)).toEqual(codigos)
    expect(screen.getByText('Guarde estes códigos. Eles não aparecem de novo.')).toBeVisible()
    expect(corpoDe('POST /auth/mfa/ativar')).toEqual({ codigo: '123456' })

    fireEvent.click(screen.getByRole('button', { name: 'Já guardei' }))
    expect(await screen.findByText('Verificação em duas etapas ligada.')).toBeVisible()
    expect(screen.getByText('3 códigos de recuperação restantes.')).toBeVisible()
  })

  it('senha errada avisa sem sair do passo', async () => {
    mockFetch({
      'GET /auth/mfa': responde(desligado),
      'POST /auth/mfa/configurar': responde({ erro: 'senha incorreta' }, 400),
    })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Ligar' }))
    const senha = screen.getByRole('form', { name: 'Confirmar senha' })
    preencher(senha, 'Senha', 'errada')
    fireEvent.click(within(senha).getByRole('button', { name: 'Continuar' }))

    expect(await within(senha).findByRole('alert')).toHaveTextContent('Senha incorreta.')
    expect(within(senha).getByLabelText('Senha')).toHaveValue('')
  })

  it('servidor sem chave diz que o MFA não está disponível', async () => {
    mockFetch({
      'GET /auth/mfa': responde(desligado),
      'POST /auth/mfa/configurar': responde({ erro: 'mfa indisponível' }, 503),
    })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Ligar' }))
    const senha = screen.getByRole('form', { name: 'Confirmar senha' })
    preencher(senha, 'Senha', 'minha-senha-1234')
    fireEvent.click(within(senha).getByRole('button', { name: 'Continuar' }))

    expect(await within(senha).findByRole('alert')).toHaveTextContent(
      'A verificação em duas etapas não está disponível neste servidor.',
    )
  })

  it('código errado na ativação avisa e limpa o campo', async () => {
    mockFetch({
      'GET /auth/mfa': responde(desligado),
      'POST /auth/mfa/configurar': responde(configuracao),
      'POST /auth/mfa/ativar': responde({ erro: 'código inválido' }, 400),
    })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Ligar' }))
    const senha = screen.getByRole('form', { name: 'Confirmar senha' })
    preencher(senha, 'Senha', 'minha-senha-1234')
    fireEvent.click(within(senha).getByRole('button', { name: 'Continuar' }))
    const ativar = await screen.findByRole('form', { name: 'Ativar verificação' })
    preencher(ativar, 'Código do app', '000000')
    fireEvent.click(within(ativar).getByRole('button', { name: 'Ativar' }))

    expect(await within(ativar).findByRole('alert')).toHaveTextContent('Código inválido.')
    expect(within(ativar).getByLabelText('Código do app')).toHaveValue('')
  })

  it('ligado, mostra os restantes e desliga com senha e código', async () => {
    let status = ligado
    mockFetch({
      'GET /auth/mfa': () => Promise.resolve(jsonResponse(status, 200)),
      'POST /auth/mfa/desativar': () => {
        status = desligado
        return Promise.resolve(new Response(null, { status: 204 }))
      },
    })
    renderizar()

    expect(await screen.findByText('Verificação em duas etapas ligada.')).toBeVisible()
    expect(screen.getByText('7 códigos de recuperação restantes.')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Desligar' }))
    const form = screen.getByRole('form', { name: 'Desligar verificação' })
    preencher(form, 'Senha', 'minha-senha-1234')
    preencher(form, 'Código', 'abcde-fghjk')
    fireEvent.click(within(form).getByRole('button', { name: 'Desligar' }))

    expect(await screen.findByText('Verificação em duas etapas desligada.')).toBeVisible()
    expect(corpoDe('POST /auth/mfa/desativar')).toEqual({ senha: 'minha-senha-1234', codigo: 'abcde-fghjk' })
  })

  it('429 ao desligar mostra quanto esperar', async () => {
    mockFetch({
      'GET /auth/mfa': responde(ligado),
      'POST /auth/mfa/desativar': responde({ erro: 'x' }, 429, { 'Retry-After': '4' }),
    })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Desligar' }))
    const form = screen.getByRole('form', { name: 'Desligar verificação' })
    preencher(form, 'Senha', 'minha-senha-1234')
    preencher(form, 'Código', '000000')
    fireEvent.click(within(form).getByRole('button', { name: 'Desligar' }))

    expect(await within(form).findByRole('alert')).toHaveTextContent('Muitas tentativas. Tente de novo em 4 segundos.')
  })

  it('gera novos códigos pedindo o código do app e mostra os dez uma vez', async () => {
    let status = ligado
    mockFetch({
      'GET /auth/mfa': () => Promise.resolve(jsonResponse(status, 200)),
      'POST /auth/mfa/codigos-de-recuperacao': () => {
        status = { ativo: true, codigosRestantes: 10 }
        return Promise.resolve(jsonResponse({ codigosDeRecuperacao: codigos }, 200))
      },
    })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Gerar novos códigos' }))
    const form = screen.getByRole('form', { name: 'Gerar novos códigos de recuperação' })
    preencher(form, 'Código do app', '123456')
    fireEvent.click(within(form).getByRole('button', { name: 'Gerar' }))

    const lista = await screen.findByRole('list', { name: 'Códigos de recuperação' })
    expect(within(lista).getAllByRole('listitem').map((item) => item.textContent)).toEqual(codigos)
    expect(screen.getByText(/Os códigos antigos deixaram de valer/)).toBeVisible()
    expect(corpoDe('POST /auth/mfa/codigos-de-recuperacao')).toEqual({ codigo: '123456' })

    fireEvent.click(screen.getByRole('button', { name: 'Já guardei' }))
    expect(await screen.findByText('10 códigos de recuperação restantes.')).toBeVisible()
  })

  it.each([
    [400, { erro: 'código inválido' }, {}, 'Código inválido.'],
    [409, { erro: 'mfa não está ativo' }, {}, 'A verificação em duas etapas já está desligada.'],
    [429, { erro: 'x' }, { 'Retry-After': '8' }, 'Muitas tentativas. Tente de novo em 8 segundos.'],
  ])('erro %i ao gerar códigos avisa e limpa o campo', async (status, corpo, headers, texto) => {
    mockFetch({
      'GET /auth/mfa': responde(ligado),
      'POST /auth/mfa/codigos-de-recuperacao': responde(corpo, status, headers),
    })
    renderizar()
    fireEvent.click(await screen.findByRole('button', { name: 'Gerar novos códigos' }))
    const form = screen.getByRole('form', { name: 'Gerar novos códigos de recuperação' })
    preencher(form, 'Código do app', '000000')
    fireEvent.click(within(form).getByRole('button', { name: 'Gerar' }))

    expect(await within(form).findByRole('alert')).toHaveTextContent(texto)
    expect(within(form).getByLabelText('Código do app')).toHaveValue('')
  })

  it('sessão expirada avisa quem chamou', async () => {
    mockFetch({ 'GET /auth/mfa': responde({ erro: 'x' }, 401) })
    const { aoSessaoExpirar } = renderizar()

    await vi.waitFor(() => expect(aoSessaoExpirar).toHaveBeenCalled())
  })
})
