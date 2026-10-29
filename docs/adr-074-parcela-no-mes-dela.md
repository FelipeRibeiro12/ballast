# ADR-074: Parcela conta no mês dela nas agregações por categoria

**Status:** aceito
**Data:** 2026-10-29
**Emenda:** ADR-032 (competência das parcelas, só para agregação), ADR-043, ADR-046

O número 059 está reservado pela branch `feat/onda7-front`; este é o próximo
livre depois do 073.

## Contexto

O ADR-032 grava todas as parcelas com `data_competencia` igual à data da
compra. Nos relatórios e no orçamento, que leem por competência, uma compra de
12x de R$ 1.200,00 em outubro entrava inteira em outubro. O dono pediu que cada
parcela conte só o valor dela, no mês dela.

## Decisão

- **Mês agregado da parcela.** Para lançamento com `parcela_num` preenchido, a
  competência nas agregações por categoria é o dia 1 do mês da
  `data_competencia` mais `parcela_num - 1` meses. A parcela 1 fica no mês da
  compra, a 2 no seguinte, e assim por diante. Cada parcela soma o valor da
  própria linha. Lançamento comum não muda.
- **Só na leitura.** Nenhum dado é migrado e `data_competencia`,
  `data_caixa`, saldo, fatura e projeção ficam como estão. Corrige-se o ADR-032
  apenas no que ele dizia sobre o uso da competência na agregação.
- **Uma expressão só.** `entries/AggregationCompetence.DATE` é o trecho de SQL
  (`CASE WHEN parcela_num IS NULL THEN data_competencia ELSE
  (date_trunc('month', data_competencia::timestamp) + make_interval(months =>
  parcela_num - 1))::date END`). Relatório, série mensal e orçamento usam o
  mesmo trecho, então os números concordam.
- **Mês depois de hoje.** A parcela já foi comprada (status `realizado`,
  ADR-032), então o relatório de um mês futuro mostra as parcelas daquele mês.
  O relatório do mês corrente não inclui as futuras, que caem em outros meses.
  Lançamento comum continua só até hoje.
- **Orçamento.** A parcela é gasto quando o mês dela já começou (mês de hoje ou
  antes) e previsto quando é de mês futuro. Assim o gasto de outubro bate com o
  relatório de outubro, e novembro mostra as parcelas como previsto.
- **Parcela editada ou apagada.** Segue o ADR-032: apagar uma parcela tira o
  valor dela do mês dela só; editar o valor muda só o mês dela. Trocar a
  categoria da compra inteira move todas as parcelas, cada uma no mês dela.
  Estorno é lançamento de receita e continua fora da despesa, como antes.
- **Intervalo que parte a compra.** O filtro é pelo mês agregado, não pela data
  gravada, então um mês pega as parcelas que caem nele, venha a compra de onde
  vier.

## O que mudou

- `ReportQueries.spendingByCategory` (gráfico por categoria, e com ele o
  `ResumoDasDespesas` do Início, que lê o mesmo `GET /relatorios`).
- `ReportQueries.totalsByMonth` (série receita contra despesa), para a soma das
  categorias continuar batendo com a coluna de despesa do mês.
- `BudgetQueries.consumption` (consumo por categoria).
- O Posso gastar (ADR-063, ADR-067) não agrega por categoria nem lê competência,
  e não muda. A lista `GET /lancamentos` filtra por `data_caixa` e não muda. A
  branch `feat/onda7-front` (ADR-059, `por=competencia` na lista) filtra pela
  data gravada: ao entrar, a lista por competência vai mostrar a parcela no mês
  da compra, enquanto o relatório a mostra no mês dela. Dívida registrada no
  backlog para usar o mesmo trecho lá.

## Medição

Postgres 17 (Testcontainers), um usuário com 200 mil lançamentos comuns em cinco
anos, a consulta do gráfico por categoria de um mês, `EXPLAIN ANALYZE`:

| Consulta | Tempo |
|---|---|
| antes, filtro direto em `data_competencia` | 9,8 ms |
| depois, expressão mais faixa sargável (a entregue) | 51 ms, lendo 140 mil linhas |

A expressão não usa o índice da V18. A faixa sargável acrescentada
(`data_competencia < fim` e `>= início - 47 meses`, já que `parcela_total` vai
até 48) limita a leitura, mas no pior caso testado ainda varre quatro anos do
mesmo usuário. Um usuário real tem milhares de linhas por ano, não 200 mil, e
51 ms no cenário extremo é aceitável. **Não criei índice.** Se o histórico
crescer, o caminho é um índice parcial `(usuario_id, data_competencia) WHERE
parcela_num IS NOT NULL` e separar a consulta em comuns mais parceladas.

## Alternativas descartadas

- **Gravar a competência de cada parcela no próprio mês.** Exigiria migrar
  dados e mudaria o que o ADR-032 e a fatura leem.
- **Coluna gerada com o mês agregado e índice nela.** Mais rápido, mas é schema
  novo sem necessidade medida.
- **Contar a parcela futura só quando o mês chega.** O relatório de um mês
  futuro ficaria sem as parcelas que já existem.
