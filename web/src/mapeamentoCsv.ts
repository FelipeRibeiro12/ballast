import type { MapeamentoCsv, MapeamentoCsvRequest, ParametrosDoCsv } from './api'

// Na tela as colunas contam a partir de 1; a API conta a partir de 0 (ADR-070). A conversão mora só aqui.
export type CamposDoMapeamento = {
  nome: string
  separador: MapeamentoCsv['separador']
  formatoData: MapeamentoCsv['formatoData']
  decimal: MapeamentoCsv['decimal']
  sinal: MapeamentoCsv['sinal']
  temCabecalho: boolean
  colunaData: string
  colunaValor: string
  colunaDescricao: string
  colunaId: string
}

export const TETO_DE_COLUNAS = 50
export const TAMANHO_MAXIMO_DO_NOME = 60

export function camposIniciais(): CamposDoMapeamento {
  return {
    nome: '',
    separador: 'ponto_e_virgula',
    formatoData: 'dd/MM/yyyy',
    decimal: 'virgula',
    sinal: 'negativo_despesa',
    temCabecalho: true,
    colunaData: '1',
    colunaValor: '2',
    colunaDescricao: '3',
    colunaId: '',
  }
}

export function camposDoMapeamento(mapeamento: MapeamentoCsv): CamposDoMapeamento {
  return {
    nome: mapeamento.nome,
    separador: mapeamento.separador,
    formatoData: mapeamento.formatoData,
    decimal: mapeamento.decimal,
    sinal: mapeamento.sinal,
    temCabecalho: mapeamento.temCabecalho,
    colunaData: String(mapeamento.colunaData + 1),
    colunaValor: String(mapeamento.colunaValor + 1),
    colunaDescricao: String(mapeamento.colunaDescricao + 1),
    colunaId: mapeamento.colunaId === null ? '' : String(mapeamento.colunaId + 1),
  }
}

function lerColuna(texto: string): number | null {
  if (!/^\d{1,2}$/.test(texto.trim())) return null
  const numero = Number(texto.trim())
  return numero >= 1 && numero <= TETO_DE_COLUNAS ? numero - 1 : null
}

type Leitura = { parametros: ParametrosDoCsv } | { problema: string }

function lerParametros(campos: CamposDoMapeamento): Leitura {
  const data = lerColuna(campos.colunaData)
  const valor = lerColuna(campos.colunaValor)
  const descricao = lerColuna(campos.colunaDescricao)
  if (data === null || valor === null || descricao === null) {
    return { problema: `Informe a coluna da data, do valor e da descrição, de 1 a ${TETO_DE_COLUNAS}.` }
  }
  const id = campos.colunaId.trim() === '' ? null : lerColuna(campos.colunaId)
  if (campos.colunaId.trim() !== '' && id === null) {
    return { problema: `A coluna do identificador vai de 1 a ${TETO_DE_COLUNAS}, ou fica em branco.` }
  }
  const usadas = [data, valor, descricao, ...(id === null ? [] : [id])]
  if (new Set(usadas).size !== usadas.length) {
    return { problema: 'Use colunas diferentes para cada campo.' }
  }
  return {
    parametros: {
      separador: campos.separador,
      formatoData: campos.formatoData,
      decimal: campos.decimal,
      sinal: campos.sinal,
      temCabecalho: campos.temCabecalho,
      colunaData: data,
      colunaValor: valor,
      colunaDescricao: descricao,
      colunaId: id,
    },
  }
}

// Sem o nome: a prévia não precisa dele.
export function parametrosDoMapeamento(campos: CamposDoMapeamento): ParametrosDoCsv | null {
  const leitura = lerParametros(campos)
  return 'parametros' in leitura ? leitura.parametros : null
}

export function problemaDosColunas(campos: CamposDoMapeamento): string | null {
  const leitura = lerParametros(campos)
  return 'problema' in leitura ? leitura.problema : null
}

export function nomeValido(nome: string): boolean {
  const aparado = nome.trim()
  return aparado.length >= 1 && aparado.length <= TAMANHO_MAXIMO_DO_NOME
}

export function problemaDosCampos(campos: CamposDoMapeamento): string | null {
  const colunas = problemaDosColunas(campos)
  if (colunas !== null) return colunas
  return nomeValido(campos.nome) ? null : `Dê um nome de 1 a ${TAMANHO_MAXIMO_DO_NOME} caracteres ao mapeamento.`
}

export function corpoDoMapeamento(campos: CamposDoMapeamento): MapeamentoCsvRequest | null {
  const parametros = parametrosDoMapeamento(campos)
  if (parametros === null || !nomeValido(campos.nome)) return null
  return { ...parametros, nome: campos.nome.trim() }
}
