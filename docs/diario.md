# Diário

Uma entrada por fatia fechada, a mais recente no topo.

## 2026-10-19 — F4: transferência entre contas (PR #9)

O que entrou: migration V7 com `transferencia` e o `lancamento` alargado para o tipo `transferencia` com categoria nula; o banco garante dono, tipo e contas diferentes por FK composta e um gatilho só para o mesmo lançamento não ser saída de uma e entrada de outra. `POST`, `GET` e `DELETE /transferencias`, pernas com `transferenciaId` e `direcao` na lista e protegidas em `/lancamentos`, saldo com entradas e saídas, tela com formulário de transferência e totais sem ela. O log do 500 deixou de gravar o conteúdo da linha recusada (dívida que apareceu três vezes).
Decisões: ADR-025. Fora de ADR: a transferência vive no pacote `entries` para reaproveitar a trava; `criadaEm` vem da perna de saída; a checagem de perna fica depois da trava do lançamento e antes da trava da conta.
Dívida registrada: idempotência na escrita de dinheiro, mensagem do `POST /lancamentos` com tipo transferência, log do 500 sanitizando demais (paga no começo da F5), `search_path` do gatilho.
Achados do QA: 0 bloquearam, 0 dívidas, 3 sugestões (duas viraram dívida). As correções da F3 foram reconferidas contra a API real.
Achados do revisor: 0 bloquearam, 1 dívida (o log), 3 sugestões. Security: 0 críticos, 0 altos, 1 médio (idempotência, já registrada), 2 baixos (o log e o `search_path`).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: interrupção por limite de uso no meio do backend; retomado sem perda, árvore estava limpa. Conferência em navegador das telas em 360px.

## 2026-10-19 — F3: lançamento realizado (PR #8)

O que entrou: migration V6 com `categoria` e `lancamento` (duas datas, valor positivo, FK composta com `usuario_id` para conta e para categoria com o tipo), sementes de categoria no cadastro e para quem já existia, `GET /categorias`, CRUD de lançamento com listagem por período, saldo da conta somando os realizados, tela do mês no web.
Decisões: ADR-024, emendado na fatia depois do QA (teto do saldo por conta e escrita serializada pela trava da linha da conta). Fora de ADR: `contaId` de outro usuário no filtro devolve lista vazia; mover lançamento para conta arquivada é recusado, editar e apagar em conta arquivada é permitido; a semente roda por evento síncrono na transação do cadastro.
Dívida registrada: `lock_timeout` na trava (F5), tipo da categoria filha (CRUD de categoria), paginação de lançamentos (importação), log do 500 com a exceção inteira (paga no começo da F4, terceira aparição).
Achados do QA: 1 bloqueou (dois lançamentos no teto passavam do limite do JSON, premissa errada no ADR), 1 dívida subiu junto (lançamento gravado em conta arquivada por corrida), 1 corrigido (validador recusava letra fora do plano básico).
Achados do revisor: 0 bloquearam, 2 dívidas (uma registrada, outra sem ação), 3 sugestões sem ação. Security: 0 críticos, 0 altos, 0 médios, 1 baixo (o log do 500).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: conferência em navegador das telas de contas e lançamentos em 360px.

## 2026-10-19 — F2: contas (PR #7)

O que entrou: migration V5 da tabela `conta`; `POST /contas`, `GET /contas` (`?arquivadas=true`), `GET /contas/{id}` e `PATCH /contas/{id}` escopados pelo usuário da sessão, com saldo em SQL (hoje igual ao inicial); tela de contas no web com total, criação e arquivamento, dinheiro sem float. Banco fora dá 503 em qualquer rota e o limite de 4 KiB vale para todas.
Decisões: ADR-023. Fora de ADR: id fora da forma canônica de UUID dá 404; Jackson recusa escalar em campo de texto, float em inteiro e número em enum; tamanho de nome em code points.
Dívida registrada: teste do saldo com lançamentos (na F3), teto de contas e paginação (F20.5), restore do timeout no `HealthRepository` e teste do caminho normal do `SessionQueries`, nome de conta no log de violação de CHECK, invisíveis e "İ" em nome de conta, HTML do Tomcat para `%2F`, chave JSON repetida.
Achados do QA: 1 bloqueou (30 s na primeira chamada com banco congelado, causa no aliveBypassWindow do Hikari; teto de 2 s na consulta de sessão), 5 corrigidos junto, o resto virou dívida.
Achados do revisor: 0 bloquearam, 2 dívidas, 2 sugestões sem ação. Security: 0 críticos, 0 altos, 0 médios, 3 baixos (2 viraram dívida, 1 só registro).
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios; nenhum alerta do CodeQL.
Em aberto: conferência em navegador da tela de contas em 360px.

## 2026-10-19 — F1.1: tela de cadastro (PR #6)

O que entrou: formulário de cadastro no web contra o `POST /auth/cadastro`, visível só com `VITE_REGISTRO_ABERTO=true`; depois do 201 o web entra com as mesmas credenciais. A API não mudou.
Decisões: ADR-022. Fora de ADR: o 400 mostra o `erro` do servidor, o resto é mensagem genérica.
Dívida registrada: trava no deploy contra `REGISTRO_ABERTO=true` em produção antes da F20.5 (dentro da F20.5).
Achados do revisor: 0 bloquearam. Uma dívida não procedia (o `make dev` repassa a flag do `.env` da raiz, conferido no módulo servido pelo Vite); virou uma linha no README. Três sugestões sem ação. Security: 0 críticos, 0 altos, 1 médio (a trava acima), 2 baixos sem ação.
Pipeline: verde na primeira tentativa, os 7 checks obrigatórios.
Em aberto: teste em navegador real, que segue da F1. Antes desta fatia o dono rodou o seed e entrou no web; o banco local foi recriado com `docker compose down -v` porque o volume era da versão Go, sem os papéis da ADR-021.

## 2026-10-19 — F1: cadastro e login em Java (PR #5)

O que entrou: cadastro fechado por padrão, login com argon2id, sessão por token opaco, `/auth/eu`, logout com revogação, atraso progressivo com reserva atômica, seed, limite de corpo de 4 KiB, `/v3/api-docs` desligado por padrão, papel de banco só DML (V3 e V4), tela de login de volta no web.
Decisões: ADR-020, ADR-021. Fora de ADR: `@UuidGenerator` v7 do Hibernate, `socketTimeout` de 30 s no pool e `validation-timeout` de 1 s (banco congelado), `EnvironmentPostProcessor` para validar `COOKIE_SECURE` antes do Flyway, cookie repetido conta como sem sessão.
Dívida registrada: 500 em vez de 503 nas rotas autenticadas com banco fora (F2), log da exceção inteira no 500, cobertura parcial do osv-scanner, "İ" no e-mail; M1 a M4 da auditoria seguem na F20.5.
Achados do revisor: 0 bloquearam; a escrita no histórico do Flyway virou a V4, o resto já estava no backlog. Security: 0 críticos e 0 altos, 4 médios (todos F20.5), 6 baixos. O alerta de CSRF do CodeQL foi dispensado citando o ADR-020.
QA: 3 achados na primeira rodada (banco congelado, recusa depois do Flyway, cookie repetido), corrigidos e retestados com a API real.
Pipeline: os sete checks verdes na primeira tentativa; o check de resultados do CodeQL falhou pelo alerta de CSRF até a dispensa.
Em aberto: navegador real antes da F2; processo `api` antigo na porta 8080 da máquina do dono.

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
