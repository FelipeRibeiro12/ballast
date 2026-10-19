// Centavos de fiat chegam como inteiro (ADR-005). Nada aqui passa por float.

const LIMITE_SEGURO = BigInt(Number.MAX_SAFE_INTEGER)

const formatadorBRL = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL' })

// Aceita bigint para o total das contas poder passar do limite seguro sem perder o centavo.
export function formatarCentavos(centavos: number | bigint): string {
  const valor = BigInt(centavos)
  const negativo = valor < 0n
  const digitos = (negativo ? -valor : valor).toString().padStart(3, '0')
  // O Intl aceita string decimal exata, então a divisão por 100 nunca vira Number.
  const decimal = `${negativo ? '-' : ''}${digitos.slice(0, -2)}.${digitos.slice(-2)}`
  if (!eDecimalExato(decimal)) throw new RangeError('centavos fora do formato esperado')
  return formatadorBRL.format(decimal)
}

// O tipo do Intl só aceita string numérica comprovada, por isso o guarda em vez de um cast.
function eDecimalExato(texto: string): texto is `${number}` {
  return /^-?\d+\.\d{2}$/.test(texto)
}

// Formato brasileiro: ponto separa milhar, vírgula separa centavos, no máximo duas casas.
const FORMATO_REAIS = /^(-?)(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{1,2}))?$/

export function lerCentavos(texto: string): number | null {
  const partes = FORMATO_REAIS.exec(texto.trim())
  if (partes === null) return null
  const [, sinal, inteiro, fracao = ''] = partes
  const centavos = BigInt(inteiro.replaceAll('.', '') + fracao.padEnd(2, '0'))
  if (centavos > LIMITE_SEGURO) return null
  return Number(sinal === '-' ? -centavos : centavos)
}

// Valor de lançamento: o sinal vem do tipo, então zero e negativo não passam.
export function lerValorPositivo(texto: string): number | null {
  const centavos = lerCentavos(texto)
  return centavos !== null && centavos > 0 ? centavos : null
}
