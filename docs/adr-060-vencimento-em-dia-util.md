# ADR-060: Vencimento de fatura em dia útil

**Status:** aceito
**Data:** 2026-10-28

## Contexto

A fatura vence no dia configurado, mas o banco só compensa em dia útil. Um
vencimento em sábado, domingo ou feriado paga na segunda, e o `data_caixa`
precisa seguir isso para o saldo projetado não mentir.

## Decisão

- `CardBilling.dueDate` continua devolvendo o vencimento nominal, que serve só
  para achar o mês da fatura. `CardBilling.payDate` passa o nominal para o
  próximo dia útil e vira o `data_caixa` de lançamento, recorrência e parcela.
- `Cycle.due` usa `payDate`, então a tela de faturas mostra a data real.
- `BusinessDays` conhece os feriados nacionais fixos (1/1, 21/4, 1/5, 7/9,
  12/10, 2/11, 15/11, 25/12 e 20/11 a partir de 2024) e os móveis pela Páscoa:
  Carnaval (segunda e terça), Sexta-feira Santa e Corpus Christi. Carnaval e
  Corpus Christi não são feriado de lei, mas o banco não compensa nesses dias.
- Feriado estadual e municipal não entram.
- O mês da parcela vem do nominal. Um vencimento 31 que vira 1º do mês
  seguinte não empurra as parcelas seguintes de mês.

## Consequência

Lançamento de cartão já gravado mantém o `data_caixa` antigo. Só o que for
criado ou recalculado depois usa o dia útil.
