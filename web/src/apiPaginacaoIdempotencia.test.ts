import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  fetchLancamentos,
  fetchMovimentosCripto,
  postAporte,
  postAtivoCripto,
  postCompraParcelada,
  postConta,
  postCotacao,
  postLancamento,
  postMarcacao,
  postMeta,
  postMovimentoCripto,
  postRecorrencia,
  postResgate,
  postTituloRendaFixa,
  postTransferencia,
} from './api'

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
const movimento = {
  id: 'm1',
  contaId: 'c1',
  ativoId: 'a1',
  tipo: 'compra',
  quantidade: '0.5',
  data: '2026-10-05',
  custoUsdCentavos: null,
  custoCentavos: null,
}

function pagina(corpo: unknown, proximo?: string): Response {
  return new Response(JSON.stringify(corpo), {
    status: 200,
    headers: { 'Content-Type': 'application/json', ...(proximo === undefined ? {} : { 'X-Next-Cursor': proximo }) },
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe.each([
  ['fetchLancamentos', () => fetchLancamentos('2026-10-01', '2026-10-31'), lancamento],
  ['fetchMovimentosCripto', () => fetchMovimentosCripto(), movimento],
])('%s segue o X-Next-Cursor', (_nome, ler, item) => {
  it('junta as páginas na ordem e manda o cursor da anterior', async () => {
    const respostas = [
      pagina([{ ...item, id: 'a' }], 'c1'),
      pagina([{ ...item, id: 'b' }], 'c2'),
      pagina([{ ...item, id: 'c' }]),
    ]
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(respostas.shift()!)))
    const lista = await ler()
    expect(lista.map((x) => x.id)).toEqual(['a', 'b', 'c'])
    const urls = vi.mocked(fetch).mock.calls.map(([url]) => String(url))
    expect(urls[0]).not.toContain('cursor=')
    expect(urls[1]).toContain('cursor=c1')
    expect(urls[2]).toContain('cursor=c2')
  })

  it('sem cabeçalho faz uma chamada só', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(pagina([item]))))
    await expect(ler()).resolves.toHaveLength(1)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('erro claro ao passar de 20 páginas, sem devolver lista cortada', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(pagina([item], 'sempre'))))
    await expect(ler()).rejects.toThrow(ApiError)
    await expect(ler()).rejects.toThrow(/páginas/)
    expect(fetch).toHaveBeenCalledTimes(40)
  })

  it('falha numa página do meio propaga o erro', async () => {
    const respostas = [pagina([item], 'c1'), new Response(null, { status: 500 })]
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(respostas.shift()!)))
    await expect(ler()).rejects.toThrow(ApiError)
  })
})

describe('Idempotency-Key nos POST de dinheiro', () => {
  const formato = /^[A-Za-z0-9._:-]{1,80}$/

  function chaveEnviada(i = 0): string | null {
    const init = vi.mocked(fetch).mock.calls[i][1]
    return new Headers(init?.headers).get('Idempotency-Key')
  }

  function responderVazio(): void {
    // O corpo não interessa: o teste olha só o cabeçalho, e o parse pode falhar depois.
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('{}', { status: 500 }))))
  }

  const chamadas: [string, (chave?: string) => Promise<unknown>][] = [
    ['postConta', (c) => postConta({} as never, c)],
    ['postLancamento', (c) => postLancamento({} as never, c)],
    ['postTransferencia', (c) => postTransferencia({} as never, c)],
    ['postCompraParcelada', (c) => postCompraParcelada({} as never, c)],
    ['postRecorrencia', (c) => postRecorrencia({} as never, c)],
    ['postMeta', (c) => postMeta({} as never, c)],
    ['postAporte', (c) => postAporte('m', 100, '2026-10-05', c)],
    ['postAtivoCripto', (c) => postAtivoCripto({} as never, c)],
    ['postCotacao', (c) => postCotacao('a', {} as never, c)],
    ['postMovimentoCripto', (c) => postMovimentoCripto({} as never, c)],
    ['postTituloRendaFixa', (c) => postTituloRendaFixa({} as never, c)],
    ['postMarcacao', (c) => postMarcacao('t', '2026-10-05', 100, c)],
    ['postResgate', (c) => postResgate('t', '2026-10-05', 100, c)],
  ]

  it.each(chamadas)('%s gera uma chave válida quando ninguém passa', async (_nome, chamar) => {
    responderVazio()
    await chamar().catch(() => undefined)
    expect(chaveEnviada()).toMatch(formato)
  })

  it.each(chamadas)('%s usa a chave recebida, para o retry reusar', async (_nome, chamar) => {
    responderVazio()
    await chamar('minha-chave-1').catch(() => undefined)
    await chamar('minha-chave-1').catch(() => undefined)
    expect(chaveEnviada(0)).toBe('minha-chave-1')
    expect(chaveEnviada(1)).toBe('minha-chave-1')
  })

  it('duas chamadas sem chave explícita geram chaves diferentes', async () => {
    responderVazio()
    await postLancamento({} as never).catch(() => undefined)
    await postLancamento({} as never).catch(() => undefined)
    expect(chaveEnviada(0)).not.toBe(chaveEnviada(1))
  })
})
