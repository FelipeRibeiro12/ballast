import { formatarData, ultimoDiaDoMes, somarMeses } from './datas'

// Mesma regra do ADR-031, só para mostrar a dica na tela. Quem grava a data de caixa é o servidor.
export function vencimentoDaFatura(compra: string, diaFechamento: number, diaVencimento: number): string {
  const ano = Number(compra.slice(0, 4))
  const mes = Number(compra.slice(5, 7))
  const dia = Number(compra.slice(8, 10))

  // Dia de fechamento maior que o último do mês cai no último dia (31 em fevereiro).
  const fechamentoDoMes = Math.min(diaFechamento, ultimoDiaDoMes({ ano, mes }))
  // Compra até o dia do fechamento, inclusive, entra na fatura que fecha neste mês.
  const mesDoFechamento = dia <= fechamentoDoMes ? { ano, mes } : somarMeses({ ano, mes }, 1)
  // Vencimento menor ou igual ao fechamento só pode ser no mês seguinte.
  const mesDoVencimento = diaVencimento > diaFechamento ? mesDoFechamento : somarMeses(mesDoFechamento, 1)

  return formatarData(
    mesDoVencimento.ano,
    mesDoVencimento.mes,
    Math.min(diaVencimento, ultimoDiaDoMes(mesDoVencimento)),
  )
}
