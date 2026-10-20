import { vencimentoDaFatura } from './fatura'
import { formatarData, somarMeses, ultimoDiaDoMes } from './datas'

export type ParcelaPrevia = { numero: number; valorCentavos: number; vencimento: string }

export type ResultadoDasParcelas =
  | { tipo: 'ok'; parcelas: ParcelaPrevia[] }
  | { tipo: 'parcela-zero' }

// Mesma regra do ADR-032, só para a prévia. Quem grava valores e datas é o servidor.
export function calcularParcelas(
  totalCentavos: number,
  quantidade: number,
  dataDaCompra: string,
  cartao: { diaFechamento: number; diaVencimento: number },
): ResultadoDasParcelas {
  // BigInt para a divisão não passar por float; o resto vai todo na última parcela.
  const total = BigInt(totalCentavos)
  const n = BigInt(quantidade)
  const base = total / n
  if (base < 1n) return { tipo: 'parcela-zero' }
  const resto = total - base * n

  const primeiroVencimento = vencimentoDaFatura(dataDaCompra, cartao.diaFechamento, cartao.diaVencimento)
  const anoInicial = Number(primeiroVencimento.slice(0, 4))
  const mesInicial = Number(primeiroVencimento.slice(5, 7))

  const parcelas: ParcelaPrevia[] = []
  for (let numero = 1; numero <= quantidade; numero++) {
    const ultima = numero === quantidade
    const alvo = somarMeses({ ano: anoInicial, mes: mesInicial }, numero - 1)
    // As seguintes vencem no dia do cartão, e mês curto corta no último dia.
    const dia = Math.min(cartao.diaVencimento, ultimoDiaDoMes(alvo))
    parcelas.push({
      numero,
      valorCentavos: Number(ultima ? base + resto : base),
      vencimento: formatarData(alvo.ano, alvo.mes, dia),
    })
  }
  return { tipo: 'ok', parcelas }
}
