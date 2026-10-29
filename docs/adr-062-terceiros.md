# ADR-062: Terceiros no cartão

**Status:** aceito
**Data:** 2026-10-28

## Contexto

Outras pessoas compram no cartão do dono, inclusive parcelado, e reembolsam
antes ou depois da fatura vencer. O dono quer saber quem deve quanto, sem que
isso apareça em relatório, orçamento ou Posso gastar.

## Decisão

- Tabela `terceiro` (nome único por usuário, sem diferenciar caixa, arquivável)
  e tabela de vínculo `terceiro_lancamento` (V29). O vínculo é uma linha por
  lançamento, em tabela à parte, para `lancamento` não ganhar coluna.
- **Despesa ligada é compra do terceiro. Receita ligada é reembolso.** O papel
  vem do tipo do lançamento. Transferência não liga.
- A dívida não é coluna: `devido = comprado - recebido`. Comprado soma as
  despesas ligadas que não foram canceladas, inclusive previstas e parcelas
  futuras. Recebido soma os reembolsos realizados.
- O dinheiro da fatura sai da conta do dono no vencimento como sempre. O
  reembolso é uma receita comum na conta onde cair, ligada ao terceiro. Saldo e
  saldo projetado não mudam de regra.
- Fora de relatório por categoria, totais por mês e orçamento: lançamento
  ligado a terceiro, compra ou reembolso, não conta.
- Fora do Posso gastar: a compra do terceiro não entra como gasto do dono, e o
  que o terceiro deve não entra como valor a receber. O reembolso que cai numa
  conta incluída sobe o saldo, e o Posso gastar sobe junto.
- Rotas: `GET/POST /terceiros`, `PUT/DELETE /terceiros/{id}`,
  `GET /terceiros/{id}/lancamentos`, `PUT /lancamentos/{id}/terceiro` e
  `PUT /compras-parceladas/{id}/terceiro` (liga todas as parcelas). `terceiroId`
  nulo solta o vínculo. `EntryResponse` ganha `terceiroId`.
- Terceiro com vínculo não se apaga (409): arquive. Terceiro arquivado não
  recebe vínculo novo.
- O reembolso abate o saldo geral do terceiro, não uma compra específica.
- Teto de 50 terceiros por usuário (`LIMITE_TERCEIROS`).

## Consequência

A compra de terceiro some do histórico de gastos do dono. O vínculo é feito em
duas chamadas (criar o lançamento, depois ligar), então uma falha no meio deixa
o lançamento sem terceiro até o usuário ligar de novo.

## Revisão de 2026-10-29

- `GET /terceiros/{id}/lancamentos` devolve os 200 mais recentes pela data de
  caixa, para a resposta não crescer sem limite.
- Se o lançamento, a compra ou o terceiro some entre a conferência e a gravação
  do vínculo, a rota responde 404 e não 500.
