import {
  ApiError,
  MapeamentoNaoEncontradoError,
  deleteMapeamentoCsv,
  fetchMapeamentosCsv,
  postImportacaoCsv,
  postMapeamentoCsv,
  postPreviaCsv,
} from './api'
import type { ParametrosDoCsv } from './api'

function responder(corpo: unknown, status = 200): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.resolve(new Response(JSON.stringify(corpo), { status }))),
  )
}

const parametros: ParametrosDoCsv = {
  separador: 'virgula',
  formatoData: 'dd/MM/yyyy',
  decimal: 'virgula',
  sinal: 'negativo_despesa',
  temCabecalho: false,
  colunaData: 0,
  colunaValor: 1,
  colunaDescricao: 2,
  colunaId: null,
}

describe('api de CSV', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('recusa lista de mapeamentos com enum desconhecido', async () => {
    responder([{ id: 'm', nome: 'x', separador: 'espaco' }])
    await expect(fetchMapeamentosCsv()).rejects.toThrow(ApiError)
  })

  it('recusa mapeamento criado com coluna fora de 0 a 49', async () => {
    responder(
      { id: 'm', nome: 'x', ...parametros, colunaData: 50 },
      201,
    )
    await expect(postMapeamentoCsv({ nome: 'x', ...parametros })).rejects.toThrow('formato inesperado')
  })

  it('recusa prévia com linha malformada', async () => {
    responder({ totalLinhas: 1, colunasDetectadas: 1, linhas: [{ ordem: 1, celulas: [1] }] })
    await expect(postPreviaCsv(parametros, new Blob(['a']))).rejects.toThrow(ApiError)
  })

  it('recusa envio de CSV com detalhe malformado', async () => {
    responder({ id: 'i1' }, 201)
    await expect(postImportacaoCsv('c1', 'm1', new Blob(['a']))).rejects.toThrow('formato inesperado')
  })

  it('404 vira MapeamentoNaoEncontradoError', async () => {
    responder({ erro: 'mapeamento não encontrado' }, 404)
    await expect(deleteMapeamentoCsv('m1')).rejects.toBeInstanceOf(MapeamentoNaoEncontradoError)
  })
})
