# ADR-029: Saldo projetado na F8

**Status:** aceito
**Data:** 2026-10-20

## Contexto

A F8 fecha o MVP com o fluxo de caixa dos próximos 90 dias por `data_caixa`.
O CLAUDE.md e `dados.md` fixam que o saldo projetado usa caixa e soma
realizado e previsto. Faltava decidir o que conta em cada dia, o que fazer com
a prevista vencida e o formato da resposta.

## Decisão

- **Fórmula.** O saldo projetado no dia `d` é `saldo_inicial` mais a soma, por
  `data_caixa <= d`, dos lançamentos `realizado` e `previsto` (receita soma,
  despesa subtrai, transferência pelo sinal da perna, como no ADR-025).
  `cancelado` nunca entra.
- **Prevista vencida entra.** Ela ainda não aconteceu, mas também não foi
  cancelada: o usuário pode esperar aquele dinheiro. Entra pela própria
  `data_caixa`, então já está somada no primeiro dia da série, e a resposta
  traz o total das vencidas separado para a tela avisar.
- **Realizado com data futura** entra no dia dele, não hoje. O saldo de `GET
  /contas` continua somando todo realizado (ADR-024); por isso o primeiro dia
  da projeção pode diferir do saldo atual, e a resposta traz os dois.
- **Contas.** Todas as contas ativas do usuário por padrão. Com `contaId`,
  só aquela (inclusive arquivada, se for dele). Transferência entre duas
  contas ativas se anula no total.
- **Entradas e saídas do dia.** Uma perna de transferência só conta em
  entradas ou saídas quando a outra perna está fora das contas do filtro. Sem
  `contaId`, transferência entre duas ativas não aparece como movimento; para
  conta arquivada, aparece como saída. Com `contaId`, toda perna da conta
  conta.
- **Rota.** `GET /saldo-projetado?dias=90[&contaId=]`, `dias` de 1 a 366
  (padrão 90). A série começa hoje (`America/Sao_Paulo`) e tem um ponto por
  dia: `{data, entradasCentavos, saidasCentavos, saldoCentavos}`. A resposta
  traz também `saldoAtualCentavos`, `vencidasCentavos` (soma com sinal das
  previstas vencidas) e `menorSaldo` (`{data, saldoCentavos}`, o primeiro dia
  em que o mínimo ocorre).
- **Faixa.** Se algum valor da série sair de ±9.007.199.254.740.991 centavos,
  a resposta é 400 `projeção fora do limite`. Só acontece somando várias contas
  perto do teto.
- **Consulta.** Uma consulta agregada por dia, escopada por `usuario_id`, com
  `generate_series` para os dias sem movimento. Somas em `numeric` no banco e
  `BigDecimal` na aplicação.
- **Web.** Seção "Saldo projetado" com um gráfico de linha em SVG feito à mão,
  sem biblioteca, o menor saldo destacado e a lista dos dias com movimento.
  Escolha de 30, 90 ou 180 dias e filtro por conta.

## Justificativa

O que o usuário quer saber é "quando o dinheiro acaba". Contar a vencida
mostra o cenário em que ela ainda acontece, e o total separado deixa claro de
onde vem a diferença.

Um ponto por dia, até 366, dá uma resposta pequena e deixa o web desenhar sem
interpolar.

## Alternativas descartadas

- **Excluir a vencida**: esconde dinheiro que ainda pode entrar ou sair, e a
  projeção fica otimista ou pessimista sem aviso.
- **Começar do saldo atual e somar só previstos**: o realizado com data futura
  entraria duas vezes ou em dia errado.
- **Biblioteca de gráfico**: uma linha e um ponto destacado não pagam a
  dependência.

## Consequência

`docs/contracts/api.md` ganha a rota. A F10 (cartão) muda a `data_caixa` das
compras para o vencimento da fatura, e a projeção passa a refletir isso sem
mudança aqui.
