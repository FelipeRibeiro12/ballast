import {
  ApiError,
  ContaNaoEncontradaError,
  EmailJaCadastradoError,
  EntradaInvalidaError,
  NomeDeContaRepetidoError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  LancamentoInvalidoError,
  LancamentoNaoEncontradoError,
  deleteLancamento,
  fetchCategorias,
  fetchContas,
  fetchLancamentos,
  postLancamento,
  putLancamento,
  patchConta,
  postCadastro,
  postConta,
} from './api'
import type { CadastroRequest, Conta, CriarContaRequest, Healthz, LoginRequest, Usuario } from './api'
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

describe('rotas de contas', () => {
  const conta = {
    id: '3b1c6d52-1f0e-4c7a-8f2b-9a4d5e6f7a80',
    nome: 'Nubank',
    tipo: 'corrente',
    moeda: 'BRL',
    saldoInicialCentavos: 1000,
    saldoCentavos: 1000,
    arquivada: false,
    criadaEm: '2026-10-19T12:00:00Z',
  }

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

  it('Conta e CriarContaRequest vêm do schema gerado', () => {
    expectTypeOf<Conta>().toEqualTypeOf<components['schemas']['Conta']>()
    expectTypeOf<CriarContaRequest>().toEqualTypeOf<components['schemas']['CriarContaRequest']>()
  })

  it('fetchContas pede as ativas ou as arquivadas com a sessão', async () => {
    responder([conta], 200)
    expect(await fetchContas(false)).toEqual([conta])
    expect(fetch).toHaveBeenLastCalledWith('/contas', { credentials: 'same-origin' })
    await fetchContas(true)
    expect(fetch).toHaveBeenLastCalledWith('/contas?arquivadas=true', { credentials: 'same-origin' })
  })

  it.each([[{ ...conta, saldoCentavos: '10' }], [{ ...conta, tipo: 'cripto' }], [{ ...conta, saldoCentavos: 1.5 }]])(
    'fetchContas recusa conta fora do formato %j',
    async (invalida) => {
      responder([invalida], 200)
      await expect(fetchContas(false)).rejects.toBeInstanceOf(ApiError)
    },
  )

  it('fetchContas recusa corpo que não é lista', async () => {
    responder(conta, 200)
    await expect(fetchContas(false)).rejects.toBeInstanceOf(ApiError)
  })

  it('postConta manda JSON com a sessão e devolve a conta', async () => {
    responder(conta, 201)
    const dados = { nome: 'Nubank', tipo: 'corrente', saldoInicialCentavos: 1000 } as const
    expect(await postConta(dados)).toEqual(conta)
    expect(fetch).toHaveBeenCalledWith('/contas', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dados),
    })
  })

  it('patchConta põe o id codificado na URL e manda só arquivada', async () => {
    responder({ ...conta, arquivada: true }, 200)
    await patchConta('a/b', true)
    expect(fetch).toHaveBeenCalledWith('/contas/a%2Fb', {
      method: 'PATCH',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: '{"arquivada":true}',
    })
  })

  it('mapeia 401, 404, 409, 400, 503 e o resto em erros tipados', async () => {
    const chamadas = [() => fetchContas(false), () => postConta({ nome: 'x', tipo: 'corrente', saldoInicialCentavos: 0 }), () => patchConta('1', true)]
    for (const chamar of chamadas) {
      responder({ erro: 'x' }, 401)
      await expect(chamar()).rejects.toBeInstanceOf(SessaoExpiradaError)
      responder({ erro: 'x' }, 409)
      await expect(chamar()).rejects.toBeInstanceOf(NomeDeContaRepetidoError)
      responder({ erro: 'nome vazio' }, 400)
      await expect(chamar()).rejects.toMatchObject({ mensagemServidor: 'nome vazio' })
      responder({ erro: 'x' }, 503)
      await expect(chamar()).rejects.toBeInstanceOf(ServicoIndisponivelError)
      responder({ erro: 'x' }, 404)
      await expect(chamar()).rejects.toBeInstanceOf(ContaNaoEncontradaError)
      responder({ erro: 'x' }, 500)
      const erro = await chamar().catch((e: unknown) => e)
      expect(erro).toBeInstanceOf(ApiError)
      expect(erro).not.toBeInstanceOf(SessaoExpiradaError)
    }
  })
})

describe('lançamentos e categorias', () => {
  const lancamento = {
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
  }
  const categoria = { id: 'cd1', nome: 'Mercado', tipo: 'despesa', paiId: null, cor: null, icone: null }
  const dados = {
    tipo: 'despesa' as const,
    titulo: 'Feira',
    valorCentavos: 12345,
    categoriaId: 'cd1',
    contaId: 'c1',
    data: '2026-10-05',
  }

  function responder(corpo: unknown, status: number): void {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          status === 204
            ? new Response(null, { status })
            : new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } }),
        ),
      ),
    )
  }

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetchLancamentos monta a query com e sem contaId', async () => {
    responder([lancamento], 200)
    expect(await fetchLancamentos('2026-10-01', '2026-10-31')).toEqual([lancamento])
    expect(fetch).toHaveBeenLastCalledWith('/lancamentos?de=2026-10-01&ate=2026-10-31', {
      credentials: 'same-origin',
    })
    await fetchLancamentos('2026-10-01', '2026-10-31', 'c 1')
    expect(fetch).toHaveBeenLastCalledWith('/lancamentos?de=2026-10-01&ate=2026-10-31&contaId=c+1', {
      credentials: 'same-origin',
    })
  })

  it('fetchCategorias lê a lista', async () => {
    responder([categoria], 200)
    expect(await fetchCategorias()).toEqual([categoria])
    expect(fetch).toHaveBeenCalledWith('/categorias', { credentials: 'same-origin' })
  })

  it('postLancamento e putLancamento enviam o corpo, e o id vai codificado', async () => {
    responder(lancamento, 201)
    await postLancamento(dados)
    expect(fetch).toHaveBeenCalledWith('/lancamentos', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dados),
    })
    responder(lancamento, 200)
    await putLancamento('a/b', dados)
    expect(fetch).toHaveBeenCalledWith('/lancamentos/a%2Fb', {
      method: 'PUT',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dados),
    })
  })

  it('deleteLancamento aceita 204 e codifica o id', async () => {
    responder(null, 204)
    await deleteLancamento('a/b')
    expect(fetch).toHaveBeenCalledWith('/lancamentos/a%2Fb', {
      method: 'DELETE',
      credentials: 'same-origin',
    })
  })

  it('mapeia 401, 404, 400, 503 e o resto em erros tipados', async () => {
    const chamadas = [
      () => fetchCategorias(),
      () => fetchLancamentos('2026-10-01', '2026-10-31'),
      () => postLancamento(dados),
      () => putLancamento('1', dados),
      () => deleteLancamento('1'),
    ]
    for (const chamar of chamadas) {
      responder({ erro: 'x' }, 401)
      await expect(chamar()).rejects.toBeInstanceOf(SessaoExpiradaError)
      responder({ erro: 'x' }, 404)
      await expect(chamar()).rejects.toBeInstanceOf(LancamentoNaoEncontradoError)
      responder({ erro: 'conta arquivada' }, 400)
      await expect(chamar()).rejects.toBeInstanceOf(LancamentoInvalidoError)
      await expect(chamar()).rejects.toMatchObject({ mensagemServidor: 'conta arquivada' })
      responder('sem formato', 400)
      await expect(chamar()).rejects.toMatchObject({ mensagemServidor: null })
      responder({ erro: 'x' }, 503)
      await expect(chamar()).rejects.toBeInstanceOf(ServicoIndisponivelError)
      responder({ erro: 'x' }, 500)
      const erro = await chamar().catch((e: unknown) => e)
      expect(erro).toBeInstanceOf(ApiError)
      expect(erro).not.toBeInstanceOf(SessaoExpiradaError)
      expect(erro).not.toBeInstanceOf(LancamentoInvalidoError)
    }
  })

  it.each([
    ['categorias com campo faltando', () => fetchCategorias(), [{ ...categoria, nome: undefined }]],
    ['categorias que não é lista', () => fetchCategorias(), categoria],
    ['lista com tipo desconhecido', () => fetchLancamentos('a', 'b'), [{ ...lancamento, tipo: 'transferencia' }]],
    ['lista com valor decimal', () => fetchLancamentos('a', 'b'), [{ ...lancamento, valorCentavos: 1.5 }]],
    ['lista com valor fora do inteiro seguro', () => fetchLancamentos('a', 'b'), [{ ...lancamento, valorCentavos: 2 ** 60 }]],
    ['post com campo faltando', () => postLancamento(dados), { ...lancamento, contaId: undefined }],
    ['put com status inesperado', () => putLancamento('1', dados), { ...lancamento, status: 'previsto' }],
  ])('recusa corpo fora do contrato: %s', async (_nome, chamar, corpo) => {
    responder(corpo, 200)
    await expect(chamar()).rejects.toThrow(/formato inesperado/)
  })
})
