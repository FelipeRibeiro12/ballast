# ADR-013: pgx/v5 para acesso ao Postgres

**Status:** aceito
**Data:** 2026-10-03

## Contexto

A API precisa falar com o Postgres (ADR-003). O ADR-005 exige `numeric(38,18)` para quantidade de cripto, lida no Go como `shopspring/decimal`.

## Decisão

`github.com/jackc/pgx/v5` com a interface nativa (`pgxpool`), sem `database/sql`.

## Justificativa

A interface nativa trata `numeric` sem passar por `interface{}`, que é o tipo onde o app mais precisa de precisão. O `pgxpool` dá pool e contexto por consulta.

## Consequência

O pgx lê `numeric` nativamente como `pgtype.Numeric`. Ler direto em `shopspring/decimal` exige um codec de terceiros (`pgx-shopspring-decimal`), que é dependência nova e precisa de aprovação na fatia de cripto (F14 em diante). Até lá a promessa vale só para o tipo nativo.

O código de acesso a dados fica acoplado ao pgx e não troca de banco por configuração. Isso é aceito: o banco já é decisão fechada no ADR-003.

## Alternativas descartadas

- **`lib/pq`** — em manutenção apenas.
- **pgx através de `database/sql`** — mais portátil e pior justamente no tipo `numeric`.
