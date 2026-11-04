import { formatarCentavos, formatarCentavosEmDolar } from './dinheiro'

export type Sentido = 'alta' | 'queda' | 'estavel'

export function sentidoDaVariacao(centavos: number): Sentido {
  if (centavos > 0) return 'alta'
  return centavos < 0 ? 'queda' : 'estavel'
}

// O sinal vai escrito (+ ou -), para a variação não depender só da cor.
function comSinal(centavos: number, formatar: (valor: number) => string): string {
  const sentido = sentidoDaVariacao(centavos)
  const texto = formatar(Math.abs(centavos))
  if (sentido === 'alta') return `+${texto}`
  return sentido === 'queda' ? `-${texto}` : texto
}

export function formatarVariacaoEmDolar(centavos: number): string {
  return comSinal(centavos, formatarCentavosEmDolar)
}

export function formatarVariacaoEmReais(centavos: number): string {
  return comSinal(centavos, formatarCentavos)
}

// O servidor manda o percentual em texto com 2 casas ("9.73", "-10.00"): só troca o ponto e põe o sinal.
// Nulo vira "-" porque a base da conta era zero ou negativa.
export function formatarVariacaoPercentual(percentual: string | null): string {
  if (percentual === null) return '-'
  const negativo = percentual.startsWith('-')
  const modulo = (negativo ? percentual.slice(1) : percentual).replace('.', ',')
  if (modulo === '0,00') return '0,00%'
  return `${negativo ? '-' : '+'}${modulo}%`
}
