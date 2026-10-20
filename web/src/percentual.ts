// Percentual trafega em centésimos de ponto percentual (ADR-027): 40% é 4000. Nada aqui passa por float.

const FORMATO_PERCENTUAL = /^(\d{1,3})(?:,(\d{1,2}))?$/

// Aceita de 0,01 a 99,99, que é a faixa que a API aceita por parcela.
export function lerPercentual(texto: string): number | null {
  const partes = FORMATO_PERCENTUAL.exec(texto.trim())
  if (partes === null) return null
  const [, inteiro, fracao = ''] = partes
  const centesimos = Number(inteiro) * 100 + Number(fracao.padEnd(2, '0'))
  return centesimos >= 1 && centesimos <= 9999 ? centesimos : null
}

export function formatarPercentual(centesimos: number): string {
  const inteiro = Math.trunc(centesimos / 100)
  // Sem zero à direita: 40% e 33,5% em vez de 40,00% e 33,50%.
  const fracao = String(centesimos % 100)
    .padStart(2, '0')
    .replace(/0+$/, '')
  return `${inteiro}${fracao === '' ? '' : `,${fracao}`}%`
}

// Mesma regra do servidor: trunca cada parcela e a última recebe o resto, então a soma fecha no total.
// BigInt porque total x percentual passa do inteiro seguro com valores grandes.
export function calcularParcelasPorPercentual(totalCentavos: number, percentuais: number[]): number[] {
  const total = BigInt(totalCentavos)
  let acumulado = 0n
  return percentuais.map((percentual, indice) => {
    const ultima = indice === percentuais.length - 1
    const parcela = ultima ? total - acumulado : (total * BigInt(percentual)) / 10000n
    acumulado += parcela
    return Number(parcela)
  })
}
