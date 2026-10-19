# Changelog

Fatia entregue sai do `docs/backlog.md` e vira linha aqui.

## Não lançado

- F0 — Walking skeleton: `GET /healthz` tocando o Postgres, tela que mostra o resultado, CI com dois workflows.
- F0.5 — Contrato OpenAPI: `api/openapi.yaml` como fonte da verdade, teste de aderência no Go, tipos TS gerados no build e gate no CI contra divergência.
- Migração do backend para Java 25 e Spring Boot 4 (ADR-016 a 019): `/healthz` em Spring, migrations V1 e V2 no Flyway, spec OpenAPI gerada do código, CI em Maven. A F1 em Go foi descartada e será reimplementada em Java. Quem tinha o banco da versão Go encontra o passo a passo no README.
- F1 (cadastro e login): `POST /auth/login`, `POST /auth/logout` e `GET /auth/eu`. A sessão vai no cookie `ballast_sessao` por 7 dias, sem renovação. O logout invalida a sessão no servidor.
- F1 (cadastro e login): depois de 3 falhas seguidas com o mesmo e-mail, o login espera um tempo que dobra a cada falha, até 15 minutos. Vale com ou sem conta.
- F1 (cadastro e login): tela de entrada e de saída na web. Usuário inicial criado por `make seed`. Cadastro pela API só com `REGISTRO_ABERTO=true`.
- F1 (cadastro e login): a API conecta com um papel que só tem DML, e a migração roda com outro. Dois pares novos no `.env`: `DB_USER` e `DB_MIGRATION_USER`. Quem já tem o banco local de antes precisa de `docker compose down -v`, descrito no README.
