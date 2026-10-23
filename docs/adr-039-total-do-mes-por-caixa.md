# ADR-039: Total do mês pela data de caixa

**Status:** aceito
**Data:** 2026-10-23

## Contexto

A tela de lançamentos separava os totais pelo `status`: `realizado` em
Receitas e Despesas, `previsto` em "Previsto no mês". A compra no cartão nasce
`realizado` (ADR-032) com `data_caixa` no vencimento da fatura, então caía em
Despesas antes do vencimento, enquanto o salário recorrente, ainda `previsto`,
ficava em "Previsto no mês". O dono apontou que as duas deviam seguir a mesma
regra: só é receita ou despesa o que já moveu dinheiro.

O mesmo vale para lançamento avulso com data futura, que também nasce
`realizado`.

## Decisão

- **Já aconteceu** é `realizado` com `data_caixa` até hoje
  (`America/Sao_Paulo`, pelo relógio do aparelho como no resto do web). Vai
  para Receitas e Despesas.
- **Ainda não aconteceu** é `previsto` em qualquer data, ou `realizado` com
  `data_caixa` depois de hoje. Vai para "Previsto no mês". Quando a data chega,
  passa sozinho para Receitas ou Despesas: o fato já foi registrado pelo
  usuário, só falta o dia.
- Na lista, o realizado futuro ganha o selo **Agendado**, ou **Na fatura** em
  conta de cartão.
- **Confirmar antes.** O agendado fora de cartão e fora de compra parcelada
  tem o botão Confirmar, que grava a data de hoje com o `PUT` de sempre. No
  cartão não há confirmar: a data é a da compra e antecipar é pagar a fatura.
- "Próximas previstas" no Início segue a mesma regra.
- A regra mora em `web/src/totaisDoMes.ts`. Nada muda na API.

## Justificativa

É a regra de caixa que o CLAUDE.md já fixa para saldo e projeção, aplicada aos
totais do mês, e é como o usuário lê a tela: "caiu ou não caiu".

Decidir pela data no web, e não mudar o `status` no servidor, mantém o
ADR-028 (nada se confirma sozinho) e o ADR-032 (parcela futura continua
`realizado`, para o limite do cartão ficar certo).

## Alternativas descartadas

- **Gravar lançamento com data futura como `previsto`**: ele não passaria a
  realizado na data sem um job, e o ADR-028 proíbe confirmar sozinho.
- **Somar a compra no cartão pela competência**: o mês mostraria gasto que
  ainda não saiu do caixa, que é a confusão apontada.

## Consequência

O saldo da conta em `GET /contas` continua somando todo realizado (ADR-024),
inclusive o de data futura. No cartão isso é o certo (a dívida já existe); em
conta corrente, um lançamento agendado já baixa o saldo atual. Mudar isso
contraria o ADR-024 e fica com o dono.
