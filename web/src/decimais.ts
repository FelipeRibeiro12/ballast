// Quantidade e preço de cripto ficam em texto do começo ao fim: Number não guarda 18 casas
// (ADR-005). Os leitores devolvem o decimal no formato da API ("0.5"), ou null.

// Quantidade aceita vírgula ou ponto como decimal e nenhum separador de milhar: em cripto o ponto
// decimal é comum, e "1.234" ficaria ambíguo se o ponto também separasse milhar.
const FORMATO_QUANTIDADE = /^(\d{1,20})(?:[.,](\d{1,18}))?$/

// Preço é dinheiro em reais: ponto separa milhar e vírgula separa as casas, como em lerCentavos.
const FORMATO_PRECO = /^(\d{1,3}(?:\.\d{3})+|\d+)(?:,(\d{1,8}))?$/

const MAX_DIGITOS_PRECO = 12

function temDigitoDiferenteDeZero(texto: string): boolean {
  return /[1-9]/.test(texto)
}

export function lerQuantidade(texto: string): string | null {
  const partes = FORMATO_QUANTIDADE.exec(texto.trim())
  if (partes === null) return null
  const [, inteiro, fracao] = partes
  const decimal = fracao === undefined ? inteiro : `${inteiro}.${fracao}`
  return temDigitoDiferenteDeZero(decimal) ? decimal : null
}

export function lerPreco(texto: string): string | null {
  const partes = FORMATO_PRECO.exec(texto.trim())
  if (partes === null) return null
  const [, inteiroComPontos, fracao] = partes
  const inteiro = inteiroComPontos.replaceAll('.', '')
  if (inteiro.length > MAX_DIGITOS_PRECO) return null
  const decimal = fracao === undefined ? inteiro : `${inteiro}.${fracao}`
  return temDigitoDiferenteDeZero(decimal) ? decimal : null
}

// "1234567.5" vira "1.234.567,5" mexendo só no texto.
export function formatarDecimal(decimal: string): string {
  const negativo = decimal.startsWith('-')
  const [inteiro, fracao] = (negativo ? decimal.slice(1) : decimal).split('.')
  const agrupado = inteiro.replace(/\B(?=(\d{3})+(?!\d))/g, '.')
  return `${negativo ? '-' : ''}${agrupado}${fracao === undefined ? '' : `,${fracao}`}`
}
