# Reteste QA — F1 em Java: cenários 1b, 10b e cookie repetido

**Data:** 2026-10-19
**Branch:** `feat/f1-cadastro-login`
**Código testado:** jar construído às 15:44 a partir de `347ab3f`, com as correções `40dca8d`, `74f8f93`, `20ac315` e `3c425cf`. Às 15:46 entrou `817c713` na branch, que altera só `README.md`, `CHANGELOG.md` e `docs/backlog.md`. O jar testado não depende dele.
**Referência:** relatório anterior `reports/qa/f1-java.md`, achados 1, 2 e 3.
**Ambiente:** Postgres 17 descartável em `127.0.0.1:55432` (container `ballast-qa-pg-reteste`, removido no fim), com o init de `api/src/test/resources/db/init-app-role.sql` (papel `ballast_app`, só DML). Migração pelo superusuário de teste `qa_admin`. Duas bases: `ballast_pausa`, com seed e V1 a V3 aplicadas, e `ballast_vazia`, sem nada. API na porta 18080, a partir do jar. Credenciais descartáveis inventadas; nenhuma aparece aqui. Não foram usados o `.env` nem o Postgres do compose.

## Suíte existente (linha de base)

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` | BUILD SUCCESS, 175 testes, 0 falhas, 0 erros (eram 169 no relatório anterior) |

Suíte verde antes de qualquer teste manual.

## Cenários

| # | Cenário | Comando resumido | Esperado | Obtido | Resultado |
|---|---|---|---|---|---|
| 10b, rodada 1 | `/healthz` com banco congelado, API já no ar | Uma chamada boa (200); `docker pause`; 3 × `curl --max-time 90 -w '%{time_total}'` | 503 `{"erro":"indisponível"}` em até ~2,5 s cada | 503 `{"erro":"indisponível"}` em 2,02 s, 2,02 s e 2,01 s | Passou |
| 10b, rodada 1 | `/auth/eu` com sessão válida, banco congelado | Login antes do pause; `curl --max-time 90` durante o pause | Sem contrato de tempo; só registrar | 500 `{"erro":"erro interno"}` em 2,01 s | Registrado (ver observação 1) |
| 10b, rodada 2 | API subida do zero e congelada antes da primeira chamada | Kill da API; nova subida até `Tomcat started` (nenhuma chamada antes); `docker pause`; 3 × `/healthz` | 503 em até ~2,5 s cada | 503 em 2,17 s, 2,02 s e 2,02 s | Passou |
| 10b, rodada 3 | Repetição da rodada 2, independente | Mesmo procedimento | Igual à rodada 2 | 503 em 2,12 s, 2,01 s e 2,02 s | Passou |
| 10c | Volta do banco | `docker unpause`; `GET /healthz` | 200 | 200 em 0,022 s (rodada 1) e 0,013 s (rodada 2) | Passou |
| 1b | Recusa com cookie inseguro fora de dev, base vazia | `DATABASE_URL` em `ballast_vazia`, `APP_ENV=staging`, `COOKIE_SECURE=false`, `java -jar` | API não sobe; base sem tabela, sem `flyway_schema_history` | Saída código 1, `APPLICATION FAILED TO START`, "COOKIE_SECURE=false só é aceito com APP_ENV=development". Log sem a palavra "Successfully applied": Flyway não rodou. Antes e depois: 0 tabelas em `public`. `to_regclass('public.flyway_schema_history')` nulo. `\dt`: "Did not find any relations". Porta 18080 sem resposta | Passou |
| Cookie 1 | `ballast_sessao=; ballast_sessao=<válido>` em `/auth/eu` | `curl -H "Cookie: ..."` | 401 | 401 em 0,028 s | Passou |
| Cookie 2 | `ballast_sessao=lixo; ballast_sessao=<válido>` | Idem | 401 | 401 em 0,002 s | Passou |
| Cookie 3 | `ballast_sessao=<válido>; ballast_sessao=lixo` | Idem | 401 | 401 em 0,002 s | Passou |
| Cookie 4 | `ballast_sessao=<válido>` (um cookie só) | Idem | 200 | 200 em 0,177 s | Passou |

Controles extras no mesmo teste: `ballast_sessao=` sozinho, 401; `outro=1; ballast_sessao=<válido>`, 200 (cookie alheio não atrapalha); `ballast_sessao=<válido>; ballast_sessao=<válido>` (o mesmo token duas vezes), 401; `ballast_sessao=<válido>; ballast_sessao=` (vazio depois), 401.

Os três achados do relatório anterior ficaram assim:

| Achado anterior | Situação agora |
|---|---|
| 1. `/healthz` não respondia em 2 s com o banco congelado | Resolvido. 503 em ~2 s nas 3 chamadas, nas 3 rodadas, inclusive na primeira chamada com a API recém-subida |
| 2. Migração antes da recusa de configuração | Resolvido. Recusa antes do Flyway; base continua sem tabela |
| 3. Cookie repetido dependia da ordem | Resolvido. Qualquer repetição dá 401; um cookie só dá 200 |

## Achados

### Trava

Nenhum.

### Erra em silêncio

Nenhum.

### Incomoda

**1. `/auth/eu` com banco congelado responde 500, e `/healthz` responde 503 (desenho)**

- **O que acontece:** com o Postgres pausado, `/auth/eu` com sessão válida responde `500 {"erro":"erro interno"}` em 2,01 s. `/healthz` no mesmo estado responde `503 {"erro":"indisponível"}`.
- **Como reproduzir:** subir a API na base de teste; `POST /auth/login`; `docker pause ballast-qa-pg-reteste`; `curl --max-time 90 -b <cookie> http://127.0.0.1:18080/auth/eu`.
- **O que deveria acontecer:** não há contrato escrito para isso. Coerente com o `/healthz`, o esperado seria 503 para "banco indisponível" também nas rotas, para o cliente distinguir indisponibilidade de falha interna. Não há falha de segurança aqui: a resposta não vaza nada e o tempo fica dentro do teto.
- **Gravidade:** incomoda.

### Decisão a registrar (não é bug)

**2. `ballast_sessao` repetido com o mesmo token também dá 401**

- O comando `ballast_sessao=<válido>; ballast_sessao=<válido>` dá 401. Está de acordo com `20ac315` ("cookie repetido é requisição sem sessão"). Se a decisão ficar, vale registrar na documentação de sessão, para que ninguém a trate como regressão depois.

## Verificações sem reprodução

- Cookie de outro `Path` ou `Domain` com o mesmo nome derrubando a sessão do usuário: não reproduzido. O app grava `Path=/` e host-only, então um segundo `ballast_sessao` só aparece se alguém injetar cookie no próprio host, como no relatório anterior.

## Resumo

- **Achados:** 2 no reteste. 0 trava, 0 erra em silêncio, 1 incomoda (500 vs 503 no banco congelado) e 1 decisão a registrar (cookie repetido idêntico). Os três achados da rodada anterior estão resolvidos.
- **Os que travam primeiro:** nenhum.
- **Recomendação: liberar com dívida registrada.** Os cenários 1b, 10b e o cookie repetido passam, com tempos dentro do contrato e sem regressão na suíte (175 testes verdes). Registrar a observação 1 como dívida, com a decisão de padronizar 503 ou manter 500 nas rotas com banco congelado. A decisão final é do tech-lead.

**Não cobre:** a borda de produção (HTTPS, `Secure` atrás de proxy, HSTS, IP real), navegador real e a tela de login, carga acima de algumas requisições, esgotamento do pool do Hikari sob concorrência, e rede com latência parcial (o teste foi com congelamento total do container, não com lentidão).
