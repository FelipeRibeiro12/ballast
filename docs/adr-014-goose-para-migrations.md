# ADR-014: goose como ferramenta de migration

**Status:** substituído por ADR-018
**Data:** 2026-10-03
**Substituído em:** 2026-10-19

> **Substituído pelo ADR-018.** Sem Go no projeto (ADR-016), o goose perde o
> `go.mod` que fixava a versão. As migrations, em SQL puro, portam para o
> Flyway.

## Contexto

O schema evolui por migration em SQL puro, aplicada na máquina local e no CI. A versão da ferramenta precisa ser a mesma nos dois (mesma lógica do ADR-011).

## Decisão

`github.com/pressly/goose/v3`, declarado como `tool` no `api/go.mod` (`go get -tool`) e executado com `go tool goose`. Migrations em SQL, com `Up` e `Down` no mesmo arquivo.

## Justificativa

A diretiva `tool` fixa a versão no `go.mod` sem entrar no grafo de dependências do binário da API, e dispensa instalação global.

## Consequência

O `go.sum` cresce com dependências indiretas do goose (drivers de outros bancos, SDKs de nuvem). Elas não entram no binário da API. Migration aplicada não se edita: correção é migration nova.

## Alternativas descartadas

- **golang-migrate** — equivalente em qualidade; a escolha entre os dois é independente da escolha do driver.
- **Binário global** — a versão diverge entre a máquina e o CI.
