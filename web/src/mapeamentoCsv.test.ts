import type { MapeamentoCsv } from './api'
import { camposDoMapeamento, camposIniciais, corpoDoMapeamento, parametrosDoMapeamento, problemaDosCampos } from './mapeamentoCsv'

const salvo: MapeamentoCsv = {
  id: 'm1',
  nome: 'Banco X',
  separador: 'virgula',
  formatoData: 'yyyy-MM-dd',
  decimal: 'ponto',
  sinal: 'positivo_despesa',
  temCabecalho: false,
  colunaData: 0,
  colunaValor: 49,
  colunaDescricao: 1,
  colunaId: null,
}

describe('mapeamentoCsv', () => {
  it('mostra as colunas a partir de 1 na tela', () => {
    const campos = camposDoMapeamento(salvo)
    expect(campos).toMatchObject({ colunaData: '1', colunaValor: '50', colunaDescricao: '2', colunaId: '' })
  })

  it('manda as colunas a partir de 0 para a API', () => {
    const campos = { ...camposIniciais(), nome: 'Banco X', colunaData: '1', colunaValor: '50', colunaDescricao: '2', colunaId: '4' }
    expect(corpoDoMapeamento(campos)).toMatchObject({ colunaData: 0, colunaValor: 49, colunaDescricao: 1, colunaId: 3 })
  })

  it('omite o identificador quando o campo está vazio', () => {
    const corpo = corpoDoMapeamento({ ...camposIniciais(), nome: 'Banco X' })
    expect(corpo).not.toBeNull()
    expect(corpo?.colunaId).toBeNull()
  })

  it.each([
    ['0', 'coluna zero'],
    ['51', 'coluna acima de 50'],
    ['1,5', 'decimal'],
    ['abc', 'texto'],
  ])('recusa a coluna %s (%s)', (valor) => {
    expect(parametrosDoMapeamento({ ...camposIniciais(), colunaData: valor })).toBeNull()
  })

  it('recusa colunas repetidas', () => {
    const campos = { ...camposIniciais(), nome: 'x', colunaData: '2', colunaValor: '2' }
    expect(parametrosDoMapeamento(campos)).toBeNull()
    expect(problemaDosCampos(campos)).toMatch(/colunas diferentes/i)
  })

  it('exige nome de 1 a 60 caracteres só para salvar', () => {
    expect(parametrosDoMapeamento(camposIniciais())).not.toBeNull()
    expect(corpoDoMapeamento({ ...camposIniciais(), nome: '   ' })).toBeNull()
    expect(corpoDoMapeamento({ ...camposIniciais(), nome: 'a'.repeat(61) })).toBeNull()
    expect(corpoDoMapeamento({ ...camposIniciais(), nome: 'a'.repeat(60) })).not.toBeNull()
  })
})
