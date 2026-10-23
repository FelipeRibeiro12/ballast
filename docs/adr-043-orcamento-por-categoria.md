# ADR-043: Orçamento por categoria na F16

**Status:** aceito
**Data:** 2026-10-23

## Contexto

A F16 pede teto mensal por categoria, consumo por `data_competencia` e quanto
falta. O rascunho de `dados.md` tinha uma linha por categoria e mês, e o
usuário teria de repetir o teto todo mês.

## Decisão

### Vigência

- `orcamento (usuario_id, categoria_id, desde, teto)`, com PK nas três
  primeiras. O teto vale do mês `desde` até a próxima linha da mesma categoria.
  `teto` nulo encerra o orçamento a partir dali.
- Definir no mês que já tem linha troca o teto. Os meses antes de `desde` não
  mudam.
- Só categoria de despesa do próprio usuário, garantido por FK composta com a
  coluna gerada `tipo_categoria`, como na V12. Apagar a categoria leva os
  tetos (CASCADE): o teto não tem sentido sem ela.

### Consumo

- Despesas da categoria e das filhas dela (a hierarquia tem um nível), com
  `data_competencia` no mês. Compra no cartão conta no mês da compra, e a
  parcelada conta inteira no mês da compra, porque toda parcela tem a
  competência da compra (ADR-032).
- **Gasto** é o realizado com competência até hoje, em São Paulo.
  **Previsto** é o que ainda não aconteceu: recorrência sem confirmar e
  lançamento com data depois de hoje. É a regra do ADR-039 no eixo da
  competência. Cancelado e transferência ficam fora.
- **Restante** é teto menos gasto, e fica negativo quando o gasto passa.
- Índice `lancamento (usuario_id, data_competencia, categoria_id)`, o que
  `dados.md` reservou para o orçamento. O de `data_caixa` não serve, porque a
  compra no cartão tem caixa no mês seguinte.

### Rotas

- `GET /orcamentos?mes=AAAA-MM` (padrão: o mês corrente em São Paulo).
- `PUT /orcamentos/{categoriaId}` com `tetoCentavos` e `aPartirDe`.
- `DELETE /orcamentos/{categoriaId}?aPartirDe=AAAA-MM` grava o teto nulo.
- Categoria de outro usuário, inexistente ou id que não é UUID: 404 com o
  mesmo corpo. Categoria de receita: 400.

### Tela

- Seção própria em `#/orcamento`, com a navegação por mês de Lançamentos.
  Editar e encerrar valem a partir do mês na tela.
- **Fora da barra de navegação.** Com seis itens os rótulos se sobrepõem em
  360px (conferido no Chromium): "Lançamentos", "Recorrências" e
  "Investimentos" passam da coluna de 60px. A entrada é um cartão no Início
  com os três tetos mais consumidos e o link para a tela.

## Justificativa

Vigência evita repetir o teto todo mês sem job que copie linhas e sem perder
o histórico: o teto de março continua o mesmo depois de mudar em abril.

## Alternativas descartadas

- **Uma linha por mês, como no rascunho**: o usuário define todo mês, ou um
  job copia, e o job vira mais um lugar para dar errado.
- **Gasto por caixa**: contraria `dados.md` e o CLAUDE.md, que põem orçamento
  na competência.
- **Contar o agendado como gasto**: o lançamento com data futura ainda não
  aconteceu (ADR-039).
- **Sexto item na barra com fonte menor**: "Investimentos" precisa de uns 75px
  em 11px; nenhuma fonte legível cabe em 60px.
- **Menu "Mais" na barra**: muda a navegação de todas as telas para uma seção.

## Consequência

Quando a lista de seções crescer de novo, a barra precisa de outro desenho
(menu "Mais" ou rótulos curtos), com teste em 360px.
