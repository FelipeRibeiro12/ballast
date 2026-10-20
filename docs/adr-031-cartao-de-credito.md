# ADR-031: Cartão de crédito na F10

**Status:** aceito
**Data:** 2026-10-20

## Contexto

Cartão é onde as duas datas se separam: a compra acontece num dia
(`data_competencia`) e o dinheiro sai no vencimento da fatura (`data_caixa`).
`docs/contracts/dados.md` descreve a tabela `cartao` e a regra da fatura, com
três casos de borda obrigatórios. Lançamento avulso, recorrência (ADR-026) e
confirmar com ajuste (ADR-028) gravam hoje a mesma data nas duas colunas, e
isso tem de mudar para conta de cartão. Compra parcelada é a F11.

## Decisão

### Conta de cartão

- O `CHECK` de tipo de `conta` passa a aceitar `cartao_credito`. Cada conta
  desse tipo tem exatamente uma linha em `cartao` (1:1), com `limite`,
  `dia_fechamento`, `dia_vencimento` (1 a 31) e `conta_pagamento_id`
  (conta corrente ou poupança do mesmo usuário, por FK composta).
- `POST /contas` com `tipo: cartao_credito` exige `cartao: {limiteCentavos,
  diaFechamento, diaVencimento, contaPagamentoId}`; os outros tipos não aceitam
  o campo. O `saldoInicialCentavos` do cartão é a dívida que já existia, em
  geral negativa.
- Na F10, fechamento e vencimento não se editam: mudar o ciclo exigiria
  recalcular a `data_caixa` de compras já lançadas. Corrigir é arquivar o
  cartão e criar outro. O limite também fica fixo na F10.
- `GET /contas` e `GET /contas/{id}` trazem `cartao` (nulo nos outros tipos)
  com `limiteCentavos`, os dias, `contaPagamentoId` e `disponivelCentavos`
  (limite mais o saldo, que é negativo quando há dívida).

### A regra da fatura

Para uma compra em `c`, com fechamento `F` e vencimento `V`, e dia maior que o
último do mês caindo no último dia:

1. O fechamento do mês de `c` é o dia `F` daquele mês. Se `c` for **até o
   fechamento, inclusive**, a compra entra na fatura que fecha naquele mês.
   Senão, entra na que fecha no mês seguinte.
2. Se `V > F`, a fatura vence no mesmo mês em que fecha. Se `V <= F`, vence no
   mês seguinte ao do fechamento.
3. `data_caixa` é o dia do vencimento dessa fatura.

Os três casos de `dados.md` têm teste: fechamento 31 em fevereiro (fecha em
28 ou 29), vencimento menor que fechamento e compra no próprio dia do
fechamento (entra na fatura que fecha naquele dia). A regra mora num
componente puro, como o motor de datas da recorrência.

### Onde a regra vale

- Em conta de cartão, **a data que o usuário informa é a da compra**:
  `data_competencia` recebe a data e `data_caixa` é calculada. Vale para `POST`
  e `PUT /lancamentos`, para a ocorrência materializada pela recorrência
  (ocorrência na data gerada, caixa no vencimento) e para o confirmar com
  ajuste da F7, que nesse caso troca a competência e recalcula a caixa.
- Fora de cartão nada muda: as duas datas continuam iguais.
- `PUT` que move um lançamento de ou para conta de cartão recalcula.
- Transferência para a conta do cartão (o pagamento) não segue a regra: as
  duas datas são a data informada.

### Fatura

- `GET /contas/{id}/faturas?de=AAAA-MM-DD&ate=AAAA-MM-DD` (por vencimento,
  no máximo 366 dias) lista as faturas do cartão: `vencimento`, `fechamento`,
  `totalCentavos` (despesas menos receitas com aquele vencimento, previstas e
  realizadas, sem canceladas), `pagoCentavos` (pernas de entrada de
  transferência na conta do cartão com `data_caixa` depois do vencimento
  anterior e até este), `restanteCentavos` e `situacao`: `aberta` antes do
  fechamento, `fechada` depois do fechamento com restante maior que zero,
  `paga` com restante menor ou igual a zero. Conta que não é cartão: 404.
- Pagar a fatura é uma transferência comum (ADR-025) da conta de pagamento
  para o cartão. Não há rota nova: o web oferece "Pagar fatura" com a
  transferência já preenchida pelo restante e pela data de vencimento.

### Saldo e projeção

- O saldo do cartão segue a regra de sempre (ADR-024 e ADR-025): a compra
  realizada subtrai, o pagamento soma. O teto do saldo vale igual.
- O saldo projetado (ADR-029) não muda: como a compra no cartão tem
  `data_caixa` no vencimento, a projeção já mostra o dinheiro saindo no dia
  certo.
- O limite **não** bloqueia lançamento. O app registra o que aconteceu; o
  banco do cartão é quem recusa a compra.

## Justificativa

Tratar a data informada como data da compra é o que o usuário sabe: ele
lembra quando comprou, não quando a fatura vence. Calcular a caixa no servidor
tira esse cálculo do web e do usuário.

Sem rota de pagamento, o par de pernas e a trava das duas contas são os da
F4, já testados.

Congelar fechamento e vencimento na F10 evita a pergunta mais difícil do
cartão (o que fazer com compras lançadas quando o ciclo muda) até haver
pedido.

## Alternativas descartadas

- **Fatura como tabela**: guardaria total e situação que já são derivados dos
  lançamentos; o CLAUDE.md proíbe saldo materializado pelo mesmo motivo.
- **Compra no dia do fechamento indo para a fatura seguinte**: é como alguns
  bancos fazem, mas `dados.md` fixou "até o fechamento", e o teste trava.
- **Bloquear lançamento acima do limite**: o app não autoriza compra.

## Consequência

`dados.md` registra a tabela, o `CHECK` alargado e a regra. Recorrência e o
confirmar com ajuste ganham o caso do cartão. A F11 (parcelada) reaproveita a
regra para cada parcela, com a competência igual e a caixa avançando um mês
por parcela.
