# ADR-063: Posso gastar por janela escolhida

**Status:** aceito
**Data:** 2026-10-28
**Muda:** ADR-048

## Contexto

O Posso gastar fechava a janela na véspera do próximo salário. Cada pessoa
fecha o mês de um jeito, e a compra de outubro no cartão só sai em novembro.
O dono quer escolher até que dia a conta vai, sem perder histórico.

## Decisão

- **Salário deixa de definir a janela**, e a categoria sai do cálculo. O Posso
  gastar olha tipo de conta e `data_caixa`, nada mais. A marca `salario` da
  categoria fica no banco, sem uso no cálculo.
- A janela termina, nesta ordem de prioridade:
  1. no **dia do mês escolhido** pelo usuário (`diaLimite`, de 1 a 31, cortado
     no último dia do mês curto). Hoje conta: se o dia ainda não passou, vale
     neste mês, senão no seguinte;
  2. na **véspera do próximo pagamento da fatura âncora**, o cartão ativo de
     vencimento mais tarde no mês. O pagamento usa o dia útil (ADR-060), então
     um vencimento no sábado fecha a janela na véspera da segunda. No dia do
     pagamento o ciclo novo já começou;
  3. no **último dia do mês**, sem cartão.
- `PUT /posso-gastar/configuracao` grava o `diaLimite` (nulo volta ao ciclo da
  fatura). Só uma linha de preferência muda (V30). Nenhum lançamento é lido
  ou alterado: a próxima leitura recalcula com os lançamentos que existirem.
- A resposta troca `proximoSalario` por `modo` (`dia`, `fatura`,
  `fim-do-mes`) e `diaLimite`, e ganha `rendimentoCofrinhoCentavos`.
- **Terceiros**: a compra de terceiro não entra como gasto, e o que ele deve
  não entra como valor a receber (ADR-062). O reembolso que cai numa conta
  entra pelo saldo.
- **Cofrinho**: o saldo entra como qualquer conta. O rendimento estimado
  (ADR-061) vai de hoje até o dia do menor saldo, com o saldo parado, e soma ao
  menor saldo. Antes desse dia o dinheiro ainda não rendeu o bastante para ser
  gasto.
- A conta continua sendo o menor saldo projetado na janela (ADR-048).

## Consequência

O valor muda de um dia para o outro quando a janela vira. O usuário que usava
o salário como referência passa a escolher o dia, ou deixa o ciclo da fatura.
