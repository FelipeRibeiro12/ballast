# Changelog

Fatia entregue sai do `docs/backlog.md` e vira linha aqui.

## Não lançado

- F0 — Walking skeleton: `GET /healthz` tocando o Postgres, tela que mostra o resultado, CI com dois workflows.
- F0.5 — Contrato OpenAPI: `api/openapi.yaml` como fonte da verdade, teste de aderência no Go, tipos TS gerados no build e gate no CI contra divergência.
- Migração do backend para Java 25 e Spring Boot 4 (ADR-016 a 019): `/healthz` em Spring, migrations V1 e V2 no Flyway, spec OpenAPI gerada do código, CI em Maven. A F1 em Go foi descartada e será reimplementada em Java. Quem tinha o banco da versão Go encontra o passo a passo no README.
