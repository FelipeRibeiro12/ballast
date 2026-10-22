import {
  ApiError,
  ContaInvalidaError,
  ContaNaoEncontradaError,
  EmailJaCadastradoError,
  EntradaInvalidaError,
  NomeDeContaRepetidoError,
  ServicoIndisponivelError,
  SessaoExpiradaError,
  LancamentoInvalidoError,
  LancamentoNaoEncontradoError,
  TransferenciaInvalidaError,
  TransferenciaNaoEncontradaError,
  CompraParceladaInvalidaError,
  CompraParceladaNaoEncontradaError,
  deleteCompraParcelada,
  fetchCompraParcelada,
  patchCompraParcelada,
  postCompraParcelada,
  deleteLancamento,
  deleteTransferencia,
  fetchTransferencia,
  postTransferencia,
  fetchCategorias,
  fetchContas,
  fetchLancamentos,
  postLancamento,
  putLancamento,
  patchConta,
  putCategoriasPermitidas,
  postCadastro,
  postConta,
  RecorrenciaInvalidaError,
  RecorrenciaNaoEncontradaError,
  desativarRecorrencia,
  fetchRecorrencias,
  fetchVencidos,
  fetchSaldoProjetado,
  fetchFaturas,
  ProjecaoInvalidaError,
  patchStatusLancamento,
  postRecorrencia,
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
    cartao: null,
    categoriasPermitidas: null,
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

  it.each([[{ ...conta, saldoCentavos: '10' }], [{ ...conta, tipo: 'investimento' }], [{ ...conta, saldoCentavos: 1.5 }]])(
    'fetchContas recusa conta fora do formato %j',
    async (invalida) => {
      responder([invalida], 200)
      await expect(fetchContas(false)).rejects.toBeInstanceOf(ApiError)
    },
  )

  const cartao = {
    limiteCentavos: 500000,
    diaFechamento: 31,
    diaVencimento: 10,
    contaPagamentoId: '3b1c6d52-1f0e-4c7a-8f2b-9a4d5e6f7a81',
    disponivelCentavos: 420000,
  }

  it('fetchContas aceita conta de cartão com o objeto completo', async () => {
    const deCartao = { ...conta, tipo: 'cartao_credito', cartao }
    responder([deCartao], 200)
    expect(await fetchContas(false)).toEqual([deCartao])
  })

  it.each([
    ['cartão de crédito sem cartao', { ...conta, tipo: 'cartao_credito', cartao: null }],
    ['corrente com cartao', { ...conta, cartao }],
    ['sem a chave cartao', (({ cartao: _omitido, ...resto }) => resto)({ ...conta, cartao: null })],
    ['sem a chave categoriasPermitidas', (({ categoriasPermitidas: _omitido, ...resto }) => resto)(conta)],
    ['benefício com categoriasPermitidas nula', { ...conta, tipo: 'beneficio', categoriasPermitidas: null }],
    ['corrente com lista de categorias', { ...conta, categoriasPermitidas: [] }],
    ['benefício com item que não é string', { ...conta, tipo: 'beneficio', categoriasPermitidas: [7] }],
    ['benefício com categoriasPermitidas que não é lista', { ...conta, tipo: 'beneficio', categoriasPermitidas: 'x' }],
    ['dia de fechamento 0', { ...conta, tipo: 'cartao_credito', cartao: { ...cartao, diaFechamento: 0 } }],
    ['dia de vencimento 32', { ...conta, tipo: 'cartao_credito', cartao: { ...cartao, diaVencimento: 32 } }],
    ['dia fracionado', { ...conta, tipo: 'cartao_credito', cartao: { ...cartao, diaVencimento: 1.5 } }],
    ['limite fracionado', { ...conta, tipo: 'cartao_credito', cartao: { ...cartao, limiteCentavos: 10.5 } }],
    ['disponível em string', { ...conta, tipo: 'cartao_credito', cartao: { ...cartao, disponivelCentavos: '1' } }],
    ['conta de pagamento não string', { ...conta, tipo: 'cartao_credito', cartao: { ...cartao, contaPagamentoId: 7 } }],
    ['campo faltando', { ...conta, tipo: 'cartao_credito', cartao: { ...cartao, limiteCentavos: undefined } }],
  ])('fetchContas recusa incoerência: %s', async (_nome, invalida) => {
    responder([invalida], 200)
    await expect(fetchContas(false)).rejects.toBeInstanceOf(ApiError)
  })

  it('fetchContas aceita benefício com lista vazia ou preenchida', async () => {
    const aberta = { ...conta, tipo: 'beneficio', categoriasPermitidas: [] }
    const restrita = { ...conta, id: 'b2', tipo: 'beneficio', categoriasPermitidas: ['cat-1', 'cat-2'] }
    responder([aberta, restrita], 200)
    expect(await fetchContas(false)).toEqual([aberta, restrita])
  })

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

  it('postConta manda categoriasPermitidas do benefício', async () => {
    const beneficio = { ...conta, tipo: 'beneficio', categoriasPermitidas: ['cat-1'] }
    responder(beneficio, 201)
    const dados: CriarContaRequest = { nome: 'VR', tipo: 'beneficio', saldoInicialCentavos: 0, categoriasPermitidas: ['cat-1'] }
    expect(await postConta(dados)).toEqual(beneficio)
    expect(JSON.parse(String(vi.mocked(fetch).mock.calls[0]?.[1]?.body))).toEqual(dados)
  })

  it('putCategoriasPermitidas põe o id codificado na URL, manda categoriaIds e devolve a conta', async () => {
    const beneficio = { ...conta, tipo: 'beneficio', categoriasPermitidas: ['cat-1'] }
    responder(beneficio, 200)
    expect(await putCategoriasPermitidas('a/b', ['cat-1'])).toEqual(beneficio)
    expect(fetch).toHaveBeenCalledWith('/contas/a%2Fb/categorias-permitidas', {
      method: 'PUT',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: '{"categoriaIds":["cat-1"]}',
    })
  })

  it('putCategoriasPermitidas recusa resposta fora do formato', async () => {
    responder({ ...conta, tipo: 'beneficio', categoriasPermitidas: null }, 200)
    await expect(putCategoriasPermitidas('1', [])).rejects.toBeInstanceOf(ApiError)
  })

  it('mapeia 401, 404, 409, 400, 503 e o resto em erros tipados', async () => {
    const chamadas = [
      () => fetchContas(false),
      () => postConta({ nome: 'x', tipo: 'corrente', saldoInicialCentavos: 0 }),
      () => patchConta('1', true),
      () => putCategoriasPermitidas('1', []),
    ]
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
    transferenciaId: null,
    templateId: null,
    parcelaOrdem: null,
    compraParceladaId: null,
    parcelaNum: null,
    parcelaTotal: null,
    direcao: null,
    contaId: 'c1',
    dataCompetencia: '2026-10-05',
    dataCaixa: '2026-10-05',
    criadoEm: '2026-10-05T12:00:00Z',
  }
  const perna = { ...lancamento, id: 'p1', tipo: 'transferencia', categoriaId: null, transferenciaId: 't1', direcao: 'saida' }
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

  it('fetchLancamentos aceita parcelas de 1 a N e de 2 a 48 parcelas', async () => {
    const primeira = { ...lancamento, compraParceladaId: 'l1', parcelaNum: 1, parcelaTotal: 2 }
    const ultima = { ...lancamento, id: 'l9', compraParceladaId: 'l1', parcelaNum: 48, parcelaTotal: 48 }
    responder([primeira, ultima], 200)
    expect(await fetchLancamentos('2026-10-01', '2026-10-31')).toHaveLength(2)
  })

  it('fetchLancamentos aceita perna de transferência com categoria nula', async () => {
    responder([perna, { ...perna, id: 'p2', direcao: 'entrada' }], 200)
    expect(await fetchLancamentos('2026-10-01', '2026-10-31')).toHaveLength(2)
  })

  it('fetchLancamentos aceita parcelaOrdem de 1 a 10 na ocorrência de um template', async () => {
    const parcela = { ...lancamento, id: 'o1', status: 'previsto', templateId: 'r1', parcelaOrdem: 10 }
    vi.stubGlobal(
      'fetch',
      vi.fn(() => Promise.resolve(new Response(JSON.stringify([parcela]), { status: 200 }))),
    )
    expect(await fetchLancamentos('a', 'b')).toEqual([parcela])
  })

  it('fetchLancamentos aceita ocorrência prevista e cancelada com templateId', async () => {
    const prevista = { ...lancamento, id: 'o1', status: 'previsto', templateId: 'r1' }
    const cancelada = { ...lancamento, id: 'o2', status: 'cancelado', templateId: 'r1' }
    responder([prevista, cancelada], 200)
    expect(await fetchLancamentos('2026-10-01', '2026-10-31')).toEqual([prevista, cancelada])
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
    ['lista com tipo desconhecido', () => fetchLancamentos('a', 'b'), [{ ...lancamento, tipo: 'investimento' }]],
    ['despesa sem categoria', () => fetchLancamentos('a', 'b'), [{ ...lancamento, categoriaId: null }]],
    ['despesa com direção', () => fetchLancamentos('a', 'b'), [{ ...lancamento, direcao: 'saida' }]],
    ['despesa com transferenciaId', () => fetchLancamentos('a', 'b'), [{ ...lancamento, transferenciaId: 't1' }]],
    ['lançamento sem os campos de transferência', () => fetchLancamentos('a', 'b'), [{ ...lancamento, direcao: undefined }]],
    ['perna com categoria', () => fetchLancamentos('a', 'b'), [{ ...perna, categoriaId: 'cd1' }]],
    ['perna sem transferenciaId', () => fetchLancamentos('a', 'b'), [{ ...perna, transferenciaId: null }]],
    ['perna sem direção', () => fetchLancamentos('a', 'b'), [{ ...perna, direcao: null }]],
    ['perna com direção desconhecida', () => fetchLancamentos('a', 'b'), [{ ...perna, direcao: 'lado' }]],
    ['lista com valor decimal', () => fetchLancamentos('a', 'b'), [{ ...lancamento, valorCentavos: 1.5 }]],
    ['lista com valor fora do inteiro seguro', () => fetchLancamentos('a', 'b'), [{ ...lancamento, valorCentavos: 2 ** 60 }]],
    ['post com campo faltando', () => postLancamento(dados), { ...lancamento, contaId: undefined }],
    ['put com status inesperado', () => putLancamento('1', dados), { ...lancamento, status: 'arquivado' }],
    ['lançamento sem templateId', () => fetchLancamentos('a', 'b'), [{ ...lancamento, templateId: undefined }]],
    ['templateId que não é texto', () => fetchLancamentos('a', 'b'), [{ ...lancamento, templateId: 7 }]],
    ['lançamento sem parcelaOrdem', () => fetchLancamentos('a', 'b'), [{ ...lancamento, parcelaOrdem: undefined }]],
    ['parcelaOrdem zero', () => fetchLancamentos('a', 'b'), [{ ...lancamento, templateId: 'r1', parcelaOrdem: 0 }]],
    ['parcelaOrdem acima de 10', () => fetchLancamentos('a', 'b'), [{ ...lancamento, templateId: 'r1', parcelaOrdem: 11 }]],
    ['parcelaOrdem decimal', () => fetchLancamentos('a', 'b'), [{ ...lancamento, templateId: 'r1', parcelaOrdem: 1.5 }]],
    ['parcelaOrdem sem template', () => fetchLancamentos('a', 'b'), [{ ...lancamento, parcelaOrdem: 1 }]],
    ['lançamento sem os campos da compra parcelada', () => fetchLancamentos('a', 'b'), [{ ...lancamento, parcelaNum: undefined }]],
    ['parcela sem parcelaTotal', () => fetchLancamentos('a', 'b'), [{ ...lancamento, compraParceladaId: 'l0', parcelaNum: 1 }]],
    ['parcelaNum sem compra', () => fetchLancamentos('a', 'b'), [{ ...lancamento, parcelaNum: 1, parcelaTotal: 3 }]],
    ['parcelaNum zero', () => fetchLancamentos('a', 'b'), [{ ...lancamento, compraParceladaId: 'l0', parcelaNum: 0, parcelaTotal: 3 }]],
    ['parcelaNum maior que o total', () => fetchLancamentos('a', 'b'), [{ ...lancamento, compraParceladaId: 'l0', parcelaNum: 4, parcelaTotal: 3 }]],
    ['parcelaTotal 1', () => fetchLancamentos('a', 'b'), [{ ...lancamento, compraParceladaId: 'l0', parcelaNum: 1, parcelaTotal: 1 }]],
    ['parcelaTotal 49', () => fetchLancamentos('a', 'b'), [{ ...lancamento, compraParceladaId: 'l0', parcelaNum: 1, parcelaTotal: 49 }]],
    ['parcelaNum decimal', () => fetchLancamentos('a', 'b'), [{ ...lancamento, compraParceladaId: 'l0', parcelaNum: 1.5, parcelaTotal: 3 }]],
    ['parcela que é receita', () => fetchLancamentos('a', 'b'), [{ ...lancamento, tipo: 'receita', compraParceladaId: 'l0', parcelaNum: 1, parcelaTotal: 3 }]],
    ['perna com template', () => fetchLancamentos('a', 'b'), [{ ...perna, templateId: 'r1' }]],
    ['perna prevista', () => fetchLancamentos('a', 'b'), [{ ...perna, status: 'previsto' }]],
  ])('recusa corpo fora do contrato: %s', async (_nome, chamar, corpo) => {
    responder(corpo, 200)
    await expect(chamar()).rejects.toThrow(/formato inesperado/)
  })
})

describe('transferências', () => {
  const transferencia = {
    id: 't1',
    contaOrigemId: 'c1',
    contaDestinoId: 'c2',
    valorCentavos: 5000,
    data: '2026-10-05',
    titulo: 'Transferência',
    descricao: null,
    saidaId: 'l1',
    entradaId: 'l2',
    criadaEm: '2026-10-05T12:00:00Z',
  }
  const dados = { contaOrigemId: 'c1', contaDestinoId: 'c2', valorCentavos: 5000, data: '2026-10-05' }

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

  it('postTransferencia envia o corpo e devolve a transferência', async () => {
    responder(transferencia, 201)
    expect(await postTransferencia(dados)).toEqual(transferencia)
    expect(fetch).toHaveBeenCalledWith('/transferencias', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dados),
    })
  })

  it('fetchTransferencia e deleteTransferencia codificam o id', async () => {
    responder(transferencia, 200)
    expect(await fetchTransferencia('a/b')).toEqual(transferencia)
    expect(fetch).toHaveBeenCalledWith('/transferencias/a%2Fb', { credentials: 'same-origin' })
    responder(null, 204)
    await deleteTransferencia('a/b')
    expect(fetch).toHaveBeenCalledWith('/transferencias/a%2Fb', {
      method: 'DELETE',
      credentials: 'same-origin',
    })
  })

  it('mapeia 401, 404, 400, 503 e o resto em erros tipados', async () => {
    const chamadas = [
      () => postTransferencia(dados),
      () => fetchTransferencia('1'),
      () => deleteTransferencia('1'),
    ]
    for (const chamar of chamadas) {
      responder({ erro: 'x' }, 401)
      await expect(chamar()).rejects.toBeInstanceOf(SessaoExpiradaError)
      responder({ erro: 'x' }, 404)
      await expect(chamar()).rejects.toBeInstanceOf(TransferenciaNaoEncontradaError)
      for (const mensagem of [
        'conta não encontrada',
        'conta arquivada',
        'contas devem ser diferentes',
        'saldo da conta fora do limite',
      ]) {
        responder({ erro: mensagem }, 400)
        await expect(chamar()).rejects.toBeInstanceOf(TransferenciaInvalidaError)
        await expect(chamar()).rejects.toMatchObject({ mensagemServidor: mensagem })
      }
      responder('sem formato', 400)
      await expect(chamar()).rejects.toMatchObject({ mensagemServidor: null })
      responder({ erro: 'x' }, 503)
      await expect(chamar()).rejects.toBeInstanceOf(ServicoIndisponivelError)
      responder({ erro: 'x' }, 500)
      const erro = await chamar().catch((e: unknown) => e)
      expect(erro).toBeInstanceOf(ApiError)
      expect(erro).not.toBeInstanceOf(TransferenciaInvalidaError)
    }
  })

  it.each([
    ['post com campo faltando', () => postTransferencia(dados), { ...transferencia, saidaId: undefined }],
    ['get com valor decimal', () => fetchTransferencia('1'), { ...transferencia, valorCentavos: 1.5 }],
  ])('recusa corpo fora do contrato: %s', async (_nome, chamar, corpo) => {
    responder(corpo, 200)
    await expect(chamar()).rejects.toThrow(/formato inesperado/)
  })
})

describe('recorrências e ocorrências', () => {
  const regra = {
    frequencia: 'mensal',
    intervalo: 1,
    diaDoMes: 5,
    diaDaSemana: null,
    inicio: '2026-11-05',
    fim: null,
    ocorrenciasMax: null,
  }
  const recorrencia = {
    id: 'r1',
    tipo: 'despesa',
    titulo: 'Aluguel',
    descricao: null,
    valorCentavos: 150000,
    categoriaId: 'cd1',
    contaId: 'c1',
    ativo: true,
    criadoEm: '2026-10-19T12:00:00Z',
    regra,
    divisao: null,
    proximaOcorrencia: '2026-11-05',
  }
  const lancamento = {
    id: 'o1',
    tipo: 'despesa',
    status: 'realizado',
    titulo: 'Aluguel',
    descricao: null,
    valorCentavos: 150000,
    categoriaId: 'cd1',
    transferenciaId: null,
    templateId: 'r1',
    parcelaOrdem: null,
    compraParceladaId: null,
    parcelaNum: null,
    parcelaTotal: null,
    direcao: null,
    contaId: 'c1',
    dataCompetencia: '2026-11-05',
    dataCaixa: '2026-11-05',
    criadoEm: '2026-10-19T12:00:00Z',
  }
  const dados = {
    tipo: 'despesa' as const,
    titulo: 'Aluguel',
    valorCentavos: 150000,
    categoriaId: 'cd1',
    contaId: 'c1',
    regra: { frequencia: 'mensal' as const, diaDoMes: 5, inicio: '2026-11-05' },
  }

  function responder(corpo: unknown, status: number): void {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } }),
        ),
      ),
    )
  }

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('fetchRecorrencias lê a divisão por percentual e por valor', async () => {
    const porPercentual = {
      ...recorrencia,
      id: 'r2',
      valorCentavos: 100000,
      divisao: {
        modo: 'percentual',
        parcelas: [
          { ordem: 1, diaDoMes: 15, percentualCentesimos: 3333, valorParcelaCentavos: 33330 },
          { ordem: 2, diaDoMes: 30, percentualCentesimos: 6667, valorParcelaCentavos: 66670 },
        ],
      },
    }
    const porValor = {
      ...porPercentual,
      id: 'r3',
      divisao: {
        modo: 'valor',
        parcelas: [
          { ordem: 1, diaDoMes: 15, valorCentavos: 40000, valorParcelaCentavos: 40000 },
          { ordem: 2, diaDoMes: 30, valorCentavos: 60000, valorParcelaCentavos: 60000 },
        ],
      },
    }
    responder([porPercentual, porValor], 200)
    expect(await fetchRecorrencias()).toEqual([porPercentual, porValor])
  })

  it('postRecorrencia envia a divisao no corpo', async () => {
    const comDivisao = {
      ...dados,
      regra: { frequencia: 'mensal' as const, inicio: '2026-11-05' },
      divisao: {
        modo: 'percentual' as const,
        parcelas: [
          { diaDoMes: 15, percentualCentesimos: 4000 },
          { diaDoMes: 30, percentualCentesimos: 6000 },
        ],
      },
    }
    responder({ recorrencia, ocorrenciasMaterializadas: 24 }, 201)
    await postRecorrencia(comDivisao)
    expect(vi.mocked(fetch).mock.calls[0][1]?.body).toBe(JSON.stringify(comDivisao))
  })

  it('fetchRecorrencias lê a lista', async () => {
    responder([recorrencia], 200)
    expect(await fetchRecorrencias()).toEqual([recorrencia])
    expect(fetch).toHaveBeenCalledWith('/recorrencias', { credentials: 'same-origin' })
  })

  it('postRecorrencia envia o corpo e lê a resposta aninhada', async () => {
    responder({ recorrencia, ocorrenciasMaterializadas: 12 }, 201)
    expect(await postRecorrencia(dados)).toEqual({ recorrencia, ocorrenciasMaterializadas: 12 })
    expect(fetch).toHaveBeenCalledWith('/recorrencias', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dados),
    })
  })

  it('desativarRecorrencia manda PATCH ativo false com o id codificado', async () => {
    responder({ ...recorrencia, ativo: false }, 200)
    expect((await desativarRecorrencia('a/b')).ativo).toBe(false)
    expect(fetch).toHaveBeenCalledWith('/recorrencias/a%2Fb', {
      method: 'PATCH',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ativo: false }),
    })
  })

  it('patchStatusLancamento manda PATCH status e lê o lançamento', async () => {
    responder({ ...lancamento, status: 'cancelado' }, 200)
    expect((await patchStatusLancamento('a/b', 'cancelado')).status).toBe('cancelado')
    expect(fetch).toHaveBeenCalledWith('/lancamentos/a%2Fb', {
      method: 'PATCH',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ status: 'cancelado' }),
    })
  })

  it('patchStatusLancamento manda valor e data do ajuste só quando vêm', async () => {
    responder({ ...lancamento, status: 'realizado' }, 200)
    await patchStatusLancamento('o1', 'realizado', { valorCentavos: 9900, data: '2026-10-12' })
    expect(vi.mocked(fetch).mock.calls[0]?.[1]?.body).toBe(
      JSON.stringify({ status: 'realizado', valorCentavos: 9900, data: '2026-10-12' }),
    )
    await patchStatusLancamento('o1', 'realizado', { data: '2026-10-12' })
    expect(vi.mocked(fetch).mock.calls[1]?.[1]?.body).toBe(
      JSON.stringify({ status: 'realizado', data: '2026-10-12' }),
    )
    await patchStatusLancamento('o1', 'realizado')
    expect(vi.mocked(fetch).mock.calls[2]?.[1]?.body).toBe(JSON.stringify({ status: 'realizado' }))
  })

  it('fetchVencidos lê a lista e o corte', async () => {
    responder({ lancamentos: [{ ...lancamento, status: 'previsto' }], maisAlgum: true }, 200)
    const vencidas = await fetchVencidos()
    expect(vencidas.maisAlgum).toBe(true)
    expect(vencidas.lancamentos).toHaveLength(1)
    expect(fetch).toHaveBeenCalledWith('/lancamentos/vencidos', { credentials: 'same-origin' })
  })

  it.each([
    ['sem maisAlgum', { lancamentos: [] }],
    ['maisAlgum que não é booleano', { lancamentos: [], maisAlgum: 'sim' }],
    ['lista que não é lista', { lancamentos: {}, maisAlgum: false }],
    ['item sem formato de lançamento', { lancamentos: [{ id: 'x' }], maisAlgum: false }],
    ['corpo que é lista', []],
  ])('fetchVencidos recusa %s', async (_nome, corpo) => {
    responder(corpo, 200)
    await expect(fetchVencidos()).rejects.toBeInstanceOf(ApiError)
  })

  it('mapeia 401, 404, 400, 503 e o resto em erros tipados', async () => {
    const casos = [
      { chamar: () => fetchRecorrencias(), naoEncontrado: RecorrenciaNaoEncontradaError, invalido: RecorrenciaInvalidaError },
      { chamar: () => postRecorrencia(dados), naoEncontrado: RecorrenciaNaoEncontradaError, invalido: RecorrenciaInvalidaError },
      { chamar: () => desativarRecorrencia('1'), naoEncontrado: RecorrenciaNaoEncontradaError, invalido: RecorrenciaInvalidaError },
      { chamar: () => patchStatusLancamento('1', 'realizado'), naoEncontrado: LancamentoNaoEncontradoError, invalido: LancamentoInvalidoError },
      { chamar: () => fetchVencidos(), naoEncontrado: LancamentoNaoEncontradoError, invalido: LancamentoInvalidoError },
    ]
    for (const { chamar, naoEncontrado, invalido } of casos) {
      responder({ erro: 'x' }, 401)
      await expect(chamar()).rejects.toBeInstanceOf(SessaoExpiradaError)
      responder({ erro: 'x' }, 404)
      await expect(chamar()).rejects.toBeInstanceOf(naoEncontrado)
      responder({ erro: 'fim deve ser igual ou posterior a inicio' }, 400)
      await expect(chamar()).rejects.toBeInstanceOf(invalido)
      await expect(chamar()).rejects.toMatchObject({ mensagemServidor: 'fim deve ser igual ou posterior a inicio' })
      responder('sem formato', 400)
      await expect(chamar()).rejects.toMatchObject({ mensagemServidor: null })
      responder({ erro: 'x' }, 503)
      await expect(chamar()).rejects.toBeInstanceOf(ServicoIndisponivelError)
      responder({ erro: 'x' }, 500)
      const erro = await chamar().catch((e: unknown) => e)
      expect(erro).toBeInstanceOf(ApiError)
      expect(erro).not.toBeInstanceOf(SessaoExpiradaError)
      expect(erro).not.toBeInstanceOf(invalido)
    }
  })

  it.each([
    ['lista que não é lista', () => fetchRecorrencias(), recorrencia],
    ['sem regra', () => fetchRecorrencias(), [{ ...recorrencia, regra: undefined }]],
    ['frequência desconhecida', () => fetchRecorrencias(), [{ ...recorrencia, regra: { ...regra, frequencia: 'quinzenal' } }]],
    ['intervalo que não é inteiro', () => fetchRecorrencias(), [{ ...recorrencia, regra: { ...regra, intervalo: 1.5 } }]],
    ['valor decimal', () => fetchRecorrencias(), [{ ...recorrencia, valorCentavos: 1.5 }]],
    ['tipo transferencia', () => fetchRecorrencias(), [{ ...recorrencia, tipo: 'transferencia' }]],
    ['ativo que não é booleano', () => fetchRecorrencias(), [{ ...recorrencia, ativo: 'sim' }]],
    ['sem o campo divisao', () => fetchRecorrencias(), [{ ...recorrencia, divisao: undefined }]],
    ['divisao com modo desconhecido', () => fetchRecorrencias(), [{ ...recorrencia, divisao: { modo: 'x', parcelas: [] } }]],
    ['divisao sem parcelas', () => fetchRecorrencias(), [{ ...recorrencia, divisao: { modo: 'valor' } }]],
    ['parcela sem valorParcelaCentavos', () => fetchRecorrencias(), [{ ...recorrencia, divisao: { modo: 'valor', parcelas: [{ ordem: 1, diaDoMes: 5, valorCentavos: 100 }] } }]],
    ['parcela percentual sem o campo do modo', () => fetchRecorrencias(), [{ ...recorrencia, divisao: { modo: 'percentual', parcelas: [{ ordem: 1, diaDoMes: 5, valorParcelaCentavos: 100 }] } }]],
    ['parcela com o campo do outro modo', () => fetchRecorrencias(), [{ ...recorrencia, divisao: { modo: 'valor', parcelas: [{ ordem: 1, diaDoMes: 5, valorCentavos: 100, percentualCentesimos: 10, valorParcelaCentavos: 100 }] } }]],
    ['parcela com percentual decimal', () => fetchRecorrencias(), [{ ...recorrencia, divisao: { modo: 'percentual', parcelas: [{ ordem: 1, diaDoMes: 5, percentualCentesimos: 33.33, valorParcelaCentavos: 100 }] } }]],
    ['parcela com valor decimal', () => fetchRecorrencias(), [{ ...recorrencia, divisao: { modo: 'valor', parcelas: [{ ordem: 1, diaDoMes: 5, valorCentavos: 1.5, valorParcelaCentavos: 100 }] } }]],
    ['proximaOcorrencia ausente', () => fetchRecorrencias(), [{ ...recorrencia, proximaOcorrencia: undefined }]],
    ['post sem o aninhamento', () => postRecorrencia(dados), recorrencia],
    ['post sem contagem', () => postRecorrencia(dados), { recorrencia }],
    ['patch de recorrência sem regra', () => desativarRecorrencia('1'), { ...recorrencia, regra: null }],
    ['patch de status com status desconhecido', () => patchStatusLancamento('1', 'realizado'), { ...lancamento, status: 'x' }],
  ])('recusa corpo fora do contrato: %s', async (_nome, chamar, corpo) => {
    responder(corpo, 200)
    await expect(chamar()).rejects.toThrow(/formato inesperado/)
  })
})

describe('fetchSaldoProjetado', () => {
  const saldo = {
    inicio: '2026-10-20',
    fim: '2026-10-21',
    contaId: null,
    saldoAtualCentavos: 10000,
    vencidasCentavos: -500,
    menorSaldo: { data: '2026-10-21', saldoCentavos: 8000 },
    dias: [
      { data: '2026-10-20', entradasCentavos: 0, saidasCentavos: 0, saldoCentavos: 9500 },
      { data: '2026-10-21', entradasCentavos: 0, saidasCentavos: 1500, saldoCentavos: 8000 },
    ],
  }

  function responder(corpo: unknown, status: number): void {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify(corpo), { status, headers: { 'Content-Type': 'application/json' } }),
        ),
      ),
    )
  }

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('pede os dias e lê a série', async () => {
    responder(saldo, 200)
    expect(await fetchSaldoProjetado(90)).toEqual(saldo)
    expect(fetch).toHaveBeenCalledWith('/saldo-projetado?dias=90', { credentials: 'same-origin' })
  })

  it('manda contaId quando informado', async () => {
    const contaId = '7f1e2c9a-0b6d-4f43-9a52-1c8d3e5b6a70'
    responder({ ...saldo, contaId }, 200)
    await fetchSaldoProjetado(30, contaId)
    expect(fetch).toHaveBeenCalledWith(`/saldo-projetado?dias=30&contaId=${contaId}`, {
      credentials: 'same-origin',
    })
  })

  it.each([
    ['sem dias', { ...saldo, dias: undefined }],
    ['dias vazio', { ...saldo, dias: [] }],
    ['dias que não é lista', { ...saldo, dias: {} }],
    ['dia com data fora do formato', { ...saldo, dias: [{ ...saldo.dias[0], data: '20/10/2026' }] }],
    ['dia com saldo decimal', { ...saldo, dias: [{ ...saldo.dias[0], saldoCentavos: 95.5 }] }],
    ['dia com entradas em texto', { ...saldo, dias: [{ ...saldo.dias[0], entradasCentavos: '0' }] }],
    ['dia com saída fora do inteiro seguro', { ...saldo, dias: [{ ...saldo.dias[0], saidasCentavos: 2 ** 60 }] }],
    ['sem menorSaldo', { ...saldo, menorSaldo: undefined }],
    ['menorSaldo com data inválida', { ...saldo, menorSaldo: { data: 'x', saldoCentavos: 1 } }],
    ['menorSaldo decimal', { ...saldo, menorSaldo: { data: '2026-10-21', saldoCentavos: 1.5 } }],
    ['saldoAtual decimal', { ...saldo, saldoAtualCentavos: 1.5 }],
    ['vencidas ausente', { ...saldo, vencidasCentavos: undefined }],
    ['inicio inválido', { ...saldo, inicio: 'hoje' }],
    ['fim ausente', { ...saldo, fim: undefined }],
    ['contaId que não é texto nem null', { ...saldo, contaId: 3 }],
    ['corpo que é lista', []],
  ])('recusa corpo fora do contrato: %s', async (_nome, corpo) => {
    responder(corpo, 200)
    await expect(fetchSaldoProjetado(90)).rejects.toThrow(/formato inesperado/)
  })

  it('mapeia 400, 401, 404, 503 e o resto em erros tipados', async () => {
    responder({ erro: 'dias deve ser um inteiro de 1 a 366' }, 400)
    await expect(fetchSaldoProjetado(0)).rejects.toBeInstanceOf(ProjecaoInvalidaError)
    await expect(fetchSaldoProjetado(0)).rejects.toMatchObject({
      mensagemServidor: 'dias deve ser um inteiro de 1 a 366',
    })
    responder('sem formato', 400)
    await expect(fetchSaldoProjetado(0)).rejects.toMatchObject({ mensagemServidor: null })
    responder({ erro: 'x' }, 401)
    await expect(fetchSaldoProjetado(90)).rejects.toBeInstanceOf(SessaoExpiradaError)
    responder({ erro: 'conta não encontrada' }, 404)
    await expect(fetchSaldoProjetado(90, 'x')).rejects.toBeInstanceOf(ContaNaoEncontradaError)
    responder({ erro: 'x' }, 503)
    await expect(fetchSaldoProjetado(90)).rejects.toBeInstanceOf(ServicoIndisponivelError)
    responder({ erro: 'x' }, 500)
    const erro = await fetchSaldoProjetado(90).catch((e: unknown) => e)
    expect(erro).toBeInstanceOf(ApiError)
    expect(erro).not.toBeInstanceOf(ProjecaoInvalidaError)
  })
})

describe('fetchFaturas', () => {
  const contaId = '3b1c6d52-1f0e-4c7a-8f2b-9a4d5e6f7a80'
  const fatura = {
    vencimento: '2026-11-10',
    fechamento: '2026-11-01',
    totalCentavos: 15000,
    pagoCentavos: 5000,
    restanteCentavos: 10000,
    situacao: 'fechada',
  }
  const corpo = { contaId, faturas: [fatura] }

  function responder(conteudo: unknown, status: number): void {
    vi.stubGlobal(
      'fetch',
      vi.fn(() =>
        Promise.resolve(
          new Response(JSON.stringify(conteudo), { status, headers: { 'Content-Type': 'application/json' } }),
        ),
      ),
    )
  }

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('pede o intervalo por vencimento e lê as faturas', async () => {
    responder(corpo, 200)
    expect(await fetchFaturas(contaId, '2026-07-01', '2027-04-30')).toEqual(corpo)
    expect(fetch).toHaveBeenCalledWith(`/contas/${contaId}/faturas?de=2026-07-01&ate=2027-04-30`, {
      credentials: 'same-origin',
    })
  })

  it('aceita total e restante negativos (estorno e pagamento a mais)', async () => {
    const negativa = { ...fatura, totalCentavos: -300, restanteCentavos: -300, pagoCentavos: 0, situacao: 'paga' }
    responder({ contaId, faturas: [negativa] }, 200)
    expect((await fetchFaturas(contaId, '2026-07-01', '2027-04-30')).faturas).toEqual([negativa])
  })

  it('aceita lista vazia', async () => {
    responder({ contaId, faturas: [] }, 200)
    expect((await fetchFaturas(contaId, '2026-07-01', '2027-04-30')).faturas).toEqual([])
  })

  it.each([
    ['situacao desconhecida', { ...fatura, situacao: 'vencida' }],
    ['situacao ausente', { ...fatura, situacao: undefined }],
    ['total decimal', { ...fatura, totalCentavos: 1.5 }],
    ['pago em texto', { ...fatura, pagoCentavos: '0' }],
    ['restante fora do inteiro seguro', { ...fatura, restanteCentavos: 2 ** 60 }],
    ['vencimento fora do formato', { ...fatura, vencimento: '10/11/2026' }],
    ['fechamento ausente', { ...fatura, fechamento: undefined }],
  ])('recusa fatura fora do contrato: %s', async (_nome, invalida) => {
    responder({ contaId, faturas: [invalida] }, 200)
    await expect(fetchFaturas(contaId, '2026-07-01', '2027-04-30')).rejects.toThrow(/formato inesperado/)
  })

  it.each([
    ['sem contaId', { faturas: [fatura] }],
    ['faturas que não é lista', { contaId, faturas: {} }],
    ['corpo que é lista', []],
  ])('recusa corpo fora do contrato: %s', async (_nome, invalido) => {
    responder(invalido, 200)
    await expect(fetchFaturas(contaId, '2026-07-01', '2027-04-30')).rejects.toThrow(/formato inesperado/)
  })

  it('mapeia 400, 401, 404, 503 e o resto em erros tipados', async () => {
    responder({ erro: 'fatura fora do limite' }, 400)
    await expect(fetchFaturas(contaId, 'a', 'b')).rejects.toBeInstanceOf(ContaInvalidaError)
    await expect(fetchFaturas(contaId, 'a', 'b')).rejects.toMatchObject({ mensagemServidor: 'fatura fora do limite' })
    responder({ erro: 'x' }, 401)
    await expect(fetchFaturas(contaId, 'a', 'b')).rejects.toBeInstanceOf(SessaoExpiradaError)
    responder({ erro: 'conta não encontrada' }, 404)
    await expect(fetchFaturas(contaId, 'a', 'b')).rejects.toBeInstanceOf(ContaNaoEncontradaError)
    responder({ erro: 'x' }, 503)
    await expect(fetchFaturas(contaId, 'a', 'b')).rejects.toBeInstanceOf(ServicoIndisponivelError)
    responder({ erro: 'x' }, 500)
    const erro = await fetchFaturas(contaId, 'a', 'b').catch((e: unknown) => e)
    expect(erro).toBeInstanceOf(ApiError)
    expect(erro).not.toBeInstanceOf(ContaInvalidaError)
  })
})

describe('compras parceladas', () => {
  const compra = {
    id: 'l1',
    titulo: 'Notebook',
    descricao: null,
    valorTotalCentavos: 100000,
    parcelas: 3,
    categoriaId: 'cd1',
    contaId: 'c9',
    dataCompra: '2026-10-01',
    criadaEm: '2026-10-01T12:00:00Z',
    itens: [
      { lancamentoId: 'l1', parcelaNum: 1, valorCentavos: 33333, dataCaixa: '2026-10-10' },
      { lancamentoId: 'l2', parcelaNum: 2, valorCentavos: 33333, dataCaixa: '2026-11-10' },
      { lancamentoId: 'l3', parcelaNum: 3, valorCentavos: 33334, dataCaixa: '2026-12-10' },
    ],
  }
  const dados = {
    titulo: 'Notebook',
    valorTotalCentavos: 100000,
    parcelas: 3,
    categoriaId: 'cd1',
    contaId: 'c9',
    data: '2026-10-01',
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

  it('postCompraParcelada manda o corpo em JSON e devolve a compra', async () => {
    responder(compra, 201)
    expect(await postCompraParcelada(dados)).toEqual(compra)
    expect(fetch).toHaveBeenCalledWith('/compras-parceladas', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(dados),
    })
  })

  it('fetchCompraParcelada codifica o id', async () => {
    responder(compra, 200)
    await fetchCompraParcelada('a/b')
    expect(fetch).toHaveBeenCalledWith('/compras-parceladas/a%2Fb', { credentials: 'same-origin' })
  })

  it('patchCompraParcelada manda só os campos informados', async () => {
    responder(compra, 200)
    await patchCompraParcelada('l1', { titulo: 'Outro' })
    expect(fetch).toHaveBeenCalledWith('/compras-parceladas/l1', {
      method: 'PATCH',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ titulo: 'Outro' }),
    })
  })

  it('deleteCompraParcelada aceita 204 e codifica o id', async () => {
    responder(null, 204)
    await deleteCompraParcelada('a/b')
    expect(fetch).toHaveBeenCalledWith('/compras-parceladas/a%2Fb', {
      method: 'DELETE',
      credentials: 'same-origin',
    })
  })

  it('mapeia 401, 404, 400, 503 e o resto em erros tipados', async () => {
    const chamadas = [
      () => postCompraParcelada(dados),
      () => fetchCompraParcelada('1'),
      () => patchCompraParcelada('1', { titulo: 'x' }),
      () => deleteCompraParcelada('1'),
    ]
    for (const chamar of chamadas) {
      responder({ erro: 'x' }, 401)
      await expect(chamar()).rejects.toBeInstanceOf(SessaoExpiradaError)
      responder({ erro: 'x' }, 404)
      await expect(chamar()).rejects.toBeInstanceOf(CompraParceladaNaoEncontradaError)
      responder({ erro: 'x' }, 503)
      await expect(chamar()).rejects.toBeInstanceOf(ServicoIndisponivelError)
      responder({ erro: 'x' }, 500)
      const erro = await chamar().catch((e: unknown) => e)
      expect(erro).toBeInstanceOf(ApiError)
      expect(erro).not.toBeInstanceOf(CompraParceladaNaoEncontradaError)
    }
  })

  it('400 carrega a mensagem do servidor, ou null se o corpo não segue o contrato', async () => {
    responder({ erro: 'cada parcela precisa ter ao menos 1 centavo' }, 400)
    const erro = await postCompraParcelada(dados).catch((e: unknown) => e)
    expect(erro).toBeInstanceOf(CompraParceladaInvalidaError)
    expect((erro as CompraParceladaInvalidaError).mensagemServidor).toBe('cada parcela precisa ter ao menos 1 centavo')
    responder({ outro: 1 }, 400)
    const semMensagem = await postCompraParcelada(dados).catch((e: unknown) => e)
    expect((semMensagem as CompraParceladaInvalidaError).mensagemServidor).toBeNull()
  })

  it.each([
    ['sem itens', { ...compra, itens: [] }],
    ['parcelas acima de 48', { ...compra, parcelas: 49 }],
    ['total decimal', { ...compra, valorTotalCentavos: 10.5 }],
    ['item com valor decimal', { ...compra, itens: [{ ...compra.itens[0], valorCentavos: 1.5 }] }],
    ['item sem vencimento', { ...compra, itens: [{ ...compra.itens[0], dataCaixa: undefined }] }],
    ['sem dataCompra', { ...compra, dataCompra: undefined }],
  ])('recusa corpo fora do contrato: %s', async (_nome, corpo) => {
    responder(corpo, 200)
    await expect(fetchCompraParcelada('l1')).rejects.toThrow(/formato inesperado/)
  })
})
