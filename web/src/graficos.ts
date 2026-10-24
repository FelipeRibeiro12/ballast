// Contas dos gráficos de Relatórios (ADR-046). Centavos chegam como inteiro e as razões saem de
// BigInt, para valor perto do inteiro seguro não virar float antes da divisão.

import type { Mes } from './datas'

const formatadorCompacto = new Intl.NumberFormat('pt-BR', {
  style: 'currency',
  currency: 'BRL',
  notation: 'compact',
  maximumFractionDigits: 1,
})

// Percentual do máximo com uma casa, truncado, para a altura ou largura da barra.
export function fracaoDoMaximo(valor: number, maximo: number): number {
  if (maximo <= 0 || valor <= 0) return 0
  const milesimos = (BigInt(valor) * 1000n) / BigInt(maximo)
  return Number(milesimos) / 10
}

// Percentual inteiro do total, arredondado para o mais perto.
export function percentualDoTotal(valor: number, total: number): number {
  if (total <= 0 || valor <= 0) return 0
  const t = BigInt(total)
  return Number((BigInt(valor) * 200n + t) / (2n * t))
}

// Mantissas da escala: o topo fica perto do maior valor e a metade ainda é um número redondo.
const MANTISSAS = [10n, 15n, 20n, 30n, 40n, 50n, 60n, 80n, 100n]

// Menor número redondo maior ou igual ao máximo, para a linha de cima do eixo.
export function topoDoEixo(maximo: number): number {
  if (maximo <= 0) return 0
  const alvo = BigInt(maximo)
  if (alvo < 10n) {
    const pequeno = [1n, 2n, 3n, 4n, 5n, 6n, 8n, 10n].find((m) => m >= alvo) ?? 10n
    return Number(pequeno)
  }
  let potencia = 1n
  while (potencia * 100n < alvo) potencia *= 10n
  const mantissa = MANTISSAS.find((m) => m * potencia >= alvo) ?? 100n
  return Number(mantissa * potencia)
}

// Base e topo do eixo de uma série que pode ficar negativa. O zero sempre entra, e cada lado sobe
// para um número redondo.
export function faixaDoEixo(minimo: number, maximo: number): { baixo: number; alto: number } {
  const baixo = minimo < 0 ? -topoDoEixo(-minimo) : 0
  const alto = maximo > 0 ? topoDoEixo(maximo) : 0
  // Série toda em zero ainda precisa de altura: R$ 1 no topo.
  return baixo === alto ? { baixo, alto: baixo + 100 } : { baixo, alto }
}

// Rótulo curto do eixo, como "R$ 6 mil". O Intl recebe string decimal exata, nunca centavos / 100.
export function formatarCompacto(centavos: number): string {
  const digitos = BigInt(centavos).toString().padStart(3, '0')
  const decimal = `${digitos.slice(0, -2)}.${digitos.slice(-2)}`
  if (!eDecimalExato(decimal)) throw new RangeError('centavos fora do formato esperado')
  return formatadorCompacto.format(decimal)
}

function eDecimalExato(texto: string): texto is `${number}` {
  return /^\d+\.\d{2}$/.test(texto)
}

const formatadorDoMesCurto = new Intl.DateTimeFormat('pt-BR', { month: 'short' })

// "out" para o rótulo da coluna; o nome completo com o ano fica no detalhe e na tabela.
export function rotuloCurtoDoMes({ ano, mes }: Mes): string {
  return formatadorDoMesCurto.format(new Date(ano, mes - 1, 1)).replace('.', '')
}
