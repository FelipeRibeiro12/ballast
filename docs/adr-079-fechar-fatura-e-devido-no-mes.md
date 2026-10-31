# ADR-079: Fechar a fatura à mão e devido do terceiro no mês

**Status:** aceito
**Data:** 2026-10-31
**Emenda:** ADR-031 (situação da fatura), ADR-062 (devido do terceiro)

## Contexto

O banco às vezes fecha a fatura antes do dia configurado, e o dono paga antes
do vencimento. A situação era só questão de data, então a fatura aberta não
aceitava pagamento. No terceiro, o devido mostrava o total de todas as
parcelas, e o dono quer saber quanto vem no mês.

## Decisão

- Tabela `fatura_fechamento` (V42): uma linha por fatura fechada à mão,
  identificada por cartão e vencimento, com a data em que foi fechada. FK
  composta com tipo constante garante dono e cartão.
- `PUT` e `DELETE /contas/{id}/faturas/{vencimento}/fechamento`. Só vale até o
  dia de fechamento, e só para a fatura que já recebe compras. Passou da data,
  409. Vencimento que não é de fatura do cartão, 404.
- Fatura fechada à mão sai de `aberta`. O pagamento conta na fatura desde o dia
  em que ela foi fechada, e a fatura anterior termina nesse dia.
- A compra feita depois do fechamento manual continua entrando na fatura pela
  regra de data (ADR-031). O app não adivinha o que o banco fez. Fica no
  backlog tratar essa compra como da fatura seguinte.
- Terceiro ganha `devidoNoMesCentavos`: compras com `data_caixa` até o fim do
  mês corrente (America/Sao_Paulo) menos todos os reembolsos realizados, sem
  negativo. Quem paga tudo de uma vez zera o mês e abate os seguintes, porque o
  reembolso entra inteiro na conta. O que ficou atrasado de meses anteriores
  continua no valor até ser pago.
- O devido total (`devidoCentavos`) continua na resposta e aparece como
  detalhe na tela.
