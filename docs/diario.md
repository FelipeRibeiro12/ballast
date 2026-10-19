# Diário

Uma entrada por fatia fechada, a mais recente no topo.

## 2026-10-19 — F1: cadastro e login em Java (PR #5, em andamento)

Parei antes do fim por limite de uso. Branch `feat/f1-cadastro-login` empurrada, PR #5 aberto.
O que entrou: rodadas A e B do backend, V3 e papel só DML, tela de login de volta no web, QA e reteste com os 3 achados corrigidos.
Decisões: ADR-020, ADR-021.
Falta: conferir o pipeline do PR #5, rodar de novo `reviewer` e `security` (interrompidos antes de responder), tratar os achados, merge com `--merge`, mover a F1 para fechada no diário e atualizar a memória do tech-lead.
Atenção: há um processo `api` antigo (pid 89846, provavelmente o binário Go) escutando em 127.0.0.1:8080; ele atrapalha `make dev`.

## 2026-10-19 — Migração do backend para Java 25 e Spring Boot 4 (PR #4)

O que entrou: ADR-016 a 019 e emendas no 005, 009 e 015. `api/` reescrito em
Spring Boot 4.1.1 com `/healthz`, migrations V1 e V2 portadas do goose para o
Flyway, spec OpenAPI gerada pelo springdoc com teste de divergência, CI em
Maven. A F1 em Go e a tela de login foram descartadas; ADR-015 e V2 ficam.
Decisões: ADR-016, ADR-017, ADR-018, ADR-019. Fora de ADR: osv-scanner na
auditoria da api, google-java-format 1.35.0 (1.37.0 quebra no spotless sob Java
25), overrides de Tomcat e Jackson por CVE enquanto o Boot 4.1.1 é a estável,
timeout de 2 s só na consulta do health, web devolvido ao estado da main.
Dívida registrada: renomear `análise estática (javascript-typescript)`;
liveness e readiness no `/healthz` (F20.5); requisitos da F1 vindos da
auditoria (usuário do Flyway separado, `/v3/api-docs` fora de dev, limite de
corpo JSON, CSRF e cabeçalhos); `Healthz` do web por `components`; `.env`
herdado pelo `pnpm install`.
Achados do revisor: 0 bloquearam. 3 "corrigir antes": timeout de consulta
(corrigido), CodeQL pendente (verde), este diário (criado). Security: 0
críticos e 0 altos, 2 médios e 6 baixos; B4 e a tag da spec corrigidos, o resto
virou dívida.
Pipeline: verde na primeira tentativa. Ruleset: `análise estática (go)` saiu
dos obrigatórios antes do push e `análise estática (api)` entrou depois do
primeiro run.
Em aberto: F1 reimplementada em Java é a próxima fatia. Quem tinha o Postgres
local da versão Go precisa de `docker compose down -v`.
