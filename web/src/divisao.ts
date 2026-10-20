import type { RecorrenciaRequest } from './api'
import { formatarCentavos, lerValorPositivo } from './dinheiro'
import { calcularParcelasPorPercentual, lerPercentual } from './percentual'

export type ModoDivisao = 'percentual' | 'valor'
export type LinhaDeParcela = { dia: string; valor: string }

type DivisaoEnviada = NonNullable<RecorrenciaRequest['divisao']>
type Resultado = { tipo: 'ok'; divisao: DivisaoEnviada } | { tipo: 'erro'; mensagem: string }

const TOTAL_PERCENTUAL = 10000

// Inteiro puro: o type=number do navegador aceita "1e2" e "1.5".
function lerDia(texto: string): number | null {
  if (!/^\d+$/.test(texto.trim())) return null
  const dia = Number(texto)
  return dia >= 1 && dia <= 31 ? dia : null
}

function lerCampo(modo: ModoDivisao, texto: string): number | null {
  return modo === 'percentual' ? lerPercentual(texto) : lerValorPositivo(texto)
}

// BigInt porque a soma de valores fixos pode passar do inteiro seguro antes de ser recusada.
export function somarLinhas(modo: ModoDivisao, linhas: LinhaDeParcela[]): bigint {
  return linhas.reduce((soma, linha) => soma + BigInt(lerCampo(modo, linha.valor) ?? 0), 0n)
}

// Só calcula quando o percentual de todas é válido e a soma fecha em 100%: sem isso "o resto na última" não quer dizer nada.
export function valoresGerados(totalCentavos: number | null, linhas: LinhaDeParcela[]): number[] | null {
  if (totalCentavos === null) return null
  const percentuais = linhas.map((linha) => lerPercentual(linha.valor))
  if (percentuais.some((percentual) => percentual === null)) return null
  const lidos = percentuais.filter((percentual) => percentual !== null)
  if (lidos.reduce((a, b) => a + b, 0) !== TOTAL_PERCENTUAL) return null
  return calcularParcelasPorPercentual(totalCentavos, lidos)
}

export function validarDivisao(modo: ModoDivisao, linhas: LinhaDeParcela[], totalCentavos: number): Resultado {
  const dias: number[] = []
  const campos: number[] = []
  for (const [indice, linha] of linhas.entries()) {
    const numero = indice + 1
    const dia = lerDia(linha.dia)
    if (dia === null) return { tipo: 'erro', mensagem: `Dia do mês da parcela ${numero} inválido. Use um inteiro de 1 a 31.` }
    const campo = lerCampo(modo, linha.valor)
    if (campo === null) {
      return {
        tipo: 'erro',
        mensagem:
          modo === 'percentual'
            ? `Percentual da parcela ${numero} inválido. Use de 0,01 a 99,99, com até duas casas.`
            : `Valor da parcela ${numero} inválido. Use o formato 1.234,56, maior que zero.`,
      }
    }
    dias.push(dia)
    campos.push(campo)
  }
  if (new Set(dias).size !== dias.length) {
    return { tipo: 'erro', mensagem: 'Os dias das parcelas devem ser diferentes.' }
  }
  if (modo === 'percentual') {
    if (somarLinhas(modo, linhas) !== BigInt(TOTAL_PERCENTUAL)) {
      return { tipo: 'erro', mensagem: 'Os percentuais das parcelas devem somar 100%.' }
    }
    if (calcularParcelasPorPercentual(totalCentavos, campos).some((valor) => valor < 1)) {
      return { tipo: 'erro', mensagem: `Toda parcela deve valer ao menos ${formatarCentavos(1)}.` }
    }
    return {
      tipo: 'ok',
      divisao: { modo, parcelas: dias.map((diaDoMes, i) => ({ diaDoMes, percentualCentesimos: campos[i] })) },
    }
  }
  if (somarLinhas(modo, linhas) !== BigInt(totalCentavos)) {
    return { tipo: 'erro', mensagem: 'Os valores das parcelas devem somar o valor total.' }
  }
  return {
    tipo: 'ok',
    divisao: { modo, parcelas: dias.map((diaDoMes, i) => ({ diaDoMes, valorCentavos: campos[i] })) },
  }
}
