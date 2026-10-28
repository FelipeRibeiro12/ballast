import type { Lancamento } from './api'

export type TotaisDoMes = {
  receitas: bigint
  despesas: bigint
  previstoReceitas: bigint
  previstoDespesas: bigint
}

// Realizado com caixa depois de hoje ainda não moveu dinheiro: compra no cartão antes do vencimento
// da fatura, ou lançamento com data futura. Ele conta como previsto até a data chegar (ADR-039).
export function aindaNaoAconteceu(lancamento: Lancamento, hoje: string): boolean {
  if (lancamento.status === 'previsto') return true
  return lancamento.status === 'realizado' && lancamento.dataCaixa > hoje
}

export function somarTotaisDoMes(lancamentos: Lancamento[], hoje: string): TotaisDoMes {
  // BigInt porque a soma de muitos lançamentos pode passar do inteiro seguro.
  const totais: TotaisDoMes = { receitas: 0n, despesas: 0n, previstoReceitas: 0n, previstoDespesas: 0n }
  for (const lancamento of lancamentos) {
    // Transferência fica fora: mover dinheiro entre contas não é ganho nem gasto.
    if (lancamento.tipo === 'transferencia' || lancamento.status === 'cancelado') continue
    // Compra e reembolso de terceiro não são ganho nem gasto do dono (ADR-062).
    if (lancamento.terceiroId != null) continue
    const valor = BigInt(lancamento.valorCentavos)
    const previsto = aindaNaoAconteceu(lancamento, hoje)
    if (lancamento.tipo === 'receita') {
      if (previsto) totais.previstoReceitas += valor
      else totais.receitas += valor
    } else if (previsto) totais.previstoDespesas += valor
    else totais.despesas += valor
  }
  return totais
}
