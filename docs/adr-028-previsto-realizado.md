# ADR-028: Previsto × realizado na F7

**Status:** aceito
**Data:** 2026-10-20

## Contexto

A F5 entregou confirmar e cancelar uma ocorrência prevista, e o `PUT` que a
marca como desgarrada (ADR-026). Faltam duas coisas do backlog: confirmar
ajustando valor e data quando o fato veio diferente do previsto, e decidir o
que acontece com a prevista vencida, que tem data antes de hoje e continua
`previsto` para sempre.

## Decisão

- **Confirmar com ajuste.** `PATCH /lancamentos/{id}` com `{"status":
  "realizado"}` passa a aceitar `valorCentavos` e `data` opcionais. Quando
  vêm, a ocorrência é gravada com o valor e a data novos (a data vale para
  competência e caixa, como no ADR-024) na mesma transação da confirmação, com
  as mesmas validações do lançamento e o teto do saldo calculado com o valor
  novo. `ocorrencia_data` não muda: a série continua sabendo qual ocorrência
  foi confirmada. Os campos só valem com `status: realizado`; com `cancelado`,
  dão 400.
- **Prevista vencida não muda sozinha.** Nem confirma nem cancela por conta
  própria: dinheiro que entrou ou saiu é o usuário quem diz.
- **Rota de vencidas.** `GET /lancamentos/vencidos` devolve as previstas do
  usuário com `data_caixa` antes de hoje, da mais antiga para a mais nova, no
  máximo 200 (o corte aparece na resposta com `maisAlgum`). "Hoje" em
  `America/Sao_Paulo`, como no ADR-026.
- **Web.** A tela de lançamentos ganha um bloco "Vencidas" no topo, só quando
  há alguma, com confirmar (com ou sem ajuste), cancelar e editar. Confirmar
  abre o valor e a data já preenchidos com os previstos.

## Justificativa

Confirmar e ajustar numa chamada só evita a sequência `PUT` mais `PATCH`, que
deixaria uma janela com o valor novo ainda previsto ou com o valor velho já
realizado.

Uma rota própria para vencidas evita varrer meses na lista mensal só para
achar o que ficou para trás.

## Alternativas descartadas

- **Confirmar automaticamente na data**: inventa um fato financeiro. Salário
  que atrasou apareceria como recebido.
- **Cancelar automaticamente depois de N dias**: some com a pendência sem o
  usuário decidir.
- **Filtro de status na listagem mensal**: o período de 366 dias não alcança
  vencida antiga.

## Consequência

`docs/contracts/api.md` ganha a rota e os campos novos do `PATCH`.
