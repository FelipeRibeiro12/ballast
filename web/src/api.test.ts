import { ApiError, EmailJaCadastradoError, EntradaInvalidaError, postCadastro } from './api'
import type { CadastroRequest, Healthz, LoginRequest, Usuario } from './api'
import type { components, paths } from './api/schema'

describe('tipos do contrato', () => {
  it('Healthz vem do schema gerado do OpenAPI', () => {
    type Corpo200 = paths['/healthz']['get']['responses']['200']['content']['application/json']
    expectTypeOf<Healthz>().toEqualTypeOf<Corpo200>()
    expectTypeOf<Healthz>().toEqualTypeOf<components['schemas']['HealthResponse']>()
    expectTypeOf<Healthz>().toEqualTypeOf<{ mensagem: string }>()
  })
})

describe('tipos do contrato de autenticação', () => {
  it('Usuario vem do schema gerado do OpenAPI', () => {
    type Corpo200 = paths['/auth/eu']['get']['responses']['200']['content']['application/json']
    expectTypeOf<Usuario>().toEqualTypeOf<Corpo200>()
    expectTypeOf<Usuario>().toEqualTypeOf<components['schemas']['Usuario']>()
    expectTypeOf<Usuario>().toEqualTypeOf<{ id: string; email: string; nome: string | null }>()
  })

  it('LoginRequest vem do schema gerado do OpenAPI', () => {
    type Corpo = paths['/auth/login']['post']['requestBody']['content']['application/json']
    expectTypeOf<LoginRequest>().toEqualTypeOf<Corpo>()
    expectTypeOf<LoginRequest>().toEqualTypeOf<components['schemas']['LoginRequest']>()
  })

  it('CadastroRequest vem do schema gerado do OpenAPI', () => {
    expectTypeOf<CadastroRequest>().toEqualTypeOf<components['schemas']['CadastroRequest']>()
  })
})

describe('postCadastro', () => {
  const usuario = { id: '7f1e2c9a-0b6d-4f43-9a52-1c8d3e5b6a70', email: 'ana@exemplo.com', nome: null }
  const dados = { email: 'ana@exemplo.com', senha: 'uma-senha-bem-longa' }

  function responder(corpo: unknown, status: number): void {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify(corpo), {
            status,
            headers: { 'Content-Type': 'application/json' },
          }),
        ),
      ),
    )
  }

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('201 envia JSON com cookies e devolve o usuário', async () => {
    responder(usuario, 201)
    await expect(postCadastro(dados)).resolves.toEqual(usuario)
    const [url, init] = vi.mocked(fetch).mock.calls[0]
    expect(url).toBe('/auth/cadastro')
    expect(init).toMatchObject({
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
    })
    expect(JSON.parse(String(init?.body))).toEqual(dados)
  })

  it('201 com formato inesperado vira ApiError', async () => {
    responder({ email: 1 }, 201)
    await expect(postCadastro(dados)).rejects.toBeInstanceOf(ApiError)
  })

  it('409 vira EmailJaCadastradoError', async () => {
    responder({ erro: 'e-mail já cadastrado' }, 409)
    await expect(postCadastro(dados)).rejects.toBeInstanceOf(EmailJaCadastradoError)
  })

  it('400 carrega a mensagem do corpo no formato do contrato', async () => {
    responder({ erro: 'senha curta demais' }, 400)
    const erro = await postCadastro(dados).catch((e: unknown) => e)
    expect(erro).toBeInstanceOf(EntradaInvalidaError)
    expect(erro).toMatchObject({ mensagemServidor: 'senha curta demais' })
  })

  it.each([[{ outra: 1 }], [{ erro: 3 }], [{ erro: '' }], ['texto']])(
    '400 com corpo %j cai em mensagem nula',
    async (corpo) => {
      responder(corpo, 400)
      const erro = await postCadastro(dados).catch((e: unknown) => e)
      expect(erro).toBeInstanceOf(EntradaInvalidaError)
      expect(erro).toMatchObject({ mensagemServidor: null })
    },
  )

  it('400 sem corpo JSON ainda é EntradaInvalidaError', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('<html>', { status: 400 }))))
    const erro = await postCadastro(dados).catch((e: unknown) => e)
    expect(erro).toBeInstanceOf(EntradaInvalidaError)
    expect(erro).toMatchObject({ mensagemServidor: null })
  })

  it.each([[401], [404], [413], [415], [500]])('%i vira ApiError simples', async (status) => {
    responder({ erro: 'x' }, status)
    const erro = await postCadastro(dados).catch((e: unknown) => e)
    expect(erro).toBeInstanceOf(ApiError)
    expect(erro).not.toBeInstanceOf(EmailJaCadastradoError)
    expect(erro).not.toBeInstanceOf(EntradaInvalidaError)
  })
})
