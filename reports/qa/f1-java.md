# Relatório QA — F1 reimplementada em Java (cadastro, login, logout, /auth/eu)

**Data:** 2026-10-19
**Branch:** `feat/f1-cadastro-login`
**Escopo:** API Java (Spring Boot, Flyway, Spring Security) de ponta a ponta, contra Postgres 17 descartável com o papel só-DML da aplicação (ADR-021). Web testada só pelo proxy do Vite e pela suíte.
**Ambiente:** Postgres `postgres:17` em `127.0.0.1:55432` (container `ballast-qa-pg`, removido no fim). API na porta 18080 (pid guardado e encerrado). Credenciais descartáveis inventadas para o teste; nenhuma aparece neste relatório. Não foram usados o `.env` nem o Postgres do compose.

## Suíte existente (linha de base)

| Comando | Resultado |
|---|---|
| `cd api && ./mvnw -B verify` | BUILD SUCCESS, 169 testes, 0 falhas, 0 erros |
| `pnpm --dir web test` | 2 arquivos, 37 testes passaram |

Suíte verde antes de qualquer teste manual, então a avaliação abaixo parte de base sã.

## Cenários

| # | Cenário | Comando resumido | Esperado | Obtido | Resultado |
|---|---|---|---|---|---|
| 1a | Subida em base vazia | `java -jar` com `DATABASE_URL` em base nova, `APP_ENV=development`, `COOKIE_SECURE=false` | Flyway aplica V1 a V3; app conecta como papel só-DML | Log: "Successfully applied 3 migrations ... now at version v3". `pg_stat_activity` mostra `ballast_app`. `ballast_app` é `rolsuper=f` e `CREATE TABLE` dá `permission denied for schema public` | Passou |
| 1b | Recusa com cookie inseguro fora de dev | Mesma base, `APP_ENV=staging COOKIE_SECURE=false` | API não sobe, mensagem sem valores | Sai com código 1 e mensagem "COOKIE_SECURE=false só é aceito com APP_ENV=development". Mas o Flyway já tinha migrado a base antes (ver achado 2) | Passou com ressalva (achado 2) |
| 2a | Seed cria usuário | `SPRING_PROFILES_ACTIVE=seed`, e-mail com espaço e maiúscula | Usuário criado, e-mail normalizado | Exit 0, "usuário criado". E-mail gravado em minúsculas, sem espaços. Nome sem espaços nas pontas | Passou |
| 2b | Seed não duplica | Segunda execução com os mesmos dados | Não cria outro | Exit 0, "usuário já existe, nada feito". Contagem continua 1 para o e-mail | Passou |
| 2c | Senha curta no seed | `SEED_SENHA` de 5 caracteres | Falha, sem ecoar o valor | Exit 1, "SEED_SENHA: senha deve ter entre 12 e 128 caracteres". Valor ausente dos logs | Passou |
| 3a | Login certo | `POST /auth/login` com e-mail em maiúscula e senha correta | 200, `Usuario`, `Set-Cookie` com HttpOnly, SameSite=Lax, Path=/, Max-Age=604800 | 200, corpo `{id,email,nome}`. Cookie `ballast_sessao; Path=/; Max-Age=604800; HttpOnly; SameSite=Lax`. Sem `Secure` porque `COOKIE_SECURE=false` (esperado) | Passou |
| 3b | `Secure` no padrão | Segunda instância sem `COOKIE_SECURE` e sem `APP_ENV` | Cookie com `Secure` | Cookie com `Secure; HttpOnly; SameSite=Lax` | Passou |
| 3c | `/auth/eu` com e sem cookie | `GET /auth/eu` com o cookie e sem | 200 do próprio usuário; 401 sem cookie | 200 com o usuário; 401 `{"erro":"não autenticado"}` | Passou |
| 4a | Logout invalida no servidor | `POST /auth/logout` e depois o cookie antigo em `/auth/eu` | 204 com Max-Age=0; cookie antigo 401 | 204, `Set-Cookie: ballast_sessao=; Max-Age=0; Expires=1970`. Cookie antigo: 401. `sessao.revogada_em` preenchido no banco | Passou |
| 4b | Logout sem sessão | `POST /auth/logout` sem cookie, e com cookie já revogado | 204 | 204 nos dois casos | Passou |
| 4c | Logout paralelo | 10 logouts simultâneos com o mesmo cookie | Todos 204, sem erro | 10 × 204 | Passou |
| 5a | E-mail inexistente e senha errada | Duas tentativas de login com 401 | Mesmo status e mesmo corpo | 401 `{"erro":"credenciais inválidas"}` nos dois. Corpos idênticos (cmp) | Passou |
| 5b | Campo extra no JSON | `{"email":..,"senha":..,"admin":true}` | 400 | 400 `{"erro":"requisição inválida"}` | Passou |
| 5c | `Content-Type: text/plain` | Corpo JSON válido | 415 | 415 `{"erro":"content-type deve ser application/json"}` | Passou |
| 5d | Sem `Content-Type` | Corpo JSON válido | 415 | 415 | Passou |
| 5e | Corpo de 5 KiB com `Content-Length` | `--data-binary` de 5033 bytes | 413 | 413 `{"erro":"corpo grande demais"}`, `Connection: close` | Passou |
| 5f | Corpo de 5 KiB em chunked | `Transfer-Encoding: chunked` | 413 | 413 com o mesmo corpo | Passou |
| 5g | Logout com corpo de 5 KiB | `Content-Length` acima do limite | 413 | 413 | Passou |
| 5h | Entradas malformadas | JSON com lixo depois, e-mail com NUL, senha vazia, senha de 129 caracteres, `email` numérico | 400 | 400 em todos | Passou |
| 6a | Atraso, e-mail sem conta | 4 erradas, depois uma 5ª | 4 × 401; 5ª 429 com Retry-After ≥ 1 | 4 × 401; 5ª 429, `Retry-After: 2` | Passou |
| 6b | Atraso, conta real e senha certa no atraso | 4 erradas, depois a senha certa | 429 também para a senha certa, corpo igual ao do 6a | 429 `Retry-After: 2`, corpo idêntico ao de 6a (cmp) | Passou |
| 6c | Fim do atraso | Espera de 17 s e senha certa | 200 e contador zerado | 200 | Passou |
| 6d | 10 tentativas paralelas erradas | 10 requisições simultâneas, e-mail sem conta | Contadas uma a uma; as barradas 429 | 4 × 401 e 6 × 429. `tentativa_login.falhas = 4` no banco, batendo com as 4 contadas | Passou |
| 7a | Cadastro fechado sem sessão | `POST /auth/cadastro` e `POST /rota-que-nao-existe` sem cookie | Mesmo status e mesmo corpo | 401 `{"erro":"não autenticado"}` nos dois. Corpos e cabeçalhos iguais | Passou |
| 7b | Cadastro fechado com sessão | Mesmos dois POSTs com cookie válido | Mesmo status e mesmo corpo | 404 `{"erro":"não encontrado"}` nos dois. Corpos e cabeçalhos iguais. Nenhum usuário criado | Passou |
| 7c | Cadastro aberto | `REGISTRO_ABERTO=true`, POST novo, POST repetido em outra caixa | 201; 409 para o repetido | 201 com e-mail em minúsculas e nome sem espaço; 409 `{"erro":"e-mail já cadastrado"}` | Passou |
| 7d | Cadastro aberto, entradas ruins | Senha de 11 caracteres, nome de 101, nome com NUL, nome vazio, sem nome | 400, 400, 400, 201 com nome nulo, 201 | Como esperado | Passou |
| 7e | Cadastro aberto em corrida | 8 POSTs simultâneos do mesmo e-mail | 1 × 201, restante 409, uma linha no banco | 1 × 201, 7 × 409, 1 linha | Passou |
| 8 | Spec fora de dev | `/v3/api-docs`, `/v3/api-docs.yaml`, `/v3/api-docs/`, `/swagger-config`, `/swagger-ui.html`, `/swagger-ui/index.html`, sem e com sessão | Nenhuma spec entregue | Sem sessão: 401 em todos. Com sessão: 404 em todos. Nenhuma spec | Passou |
| 9 | Cabeçalhos de segurança | `-D -` em `/auth/eu` autenticado, `/healthz` e login 401 | `X-Content-Type-Options`, `X-Frame-Options`, `Cache-Control` | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-cache, no-store, max-age=0, must-revalidate` nas três. `X-XSS-Protection: 0` (padrão do Spring 6). HSTS não sai em HTTP (esperado) | Passou |
| 10a | `/healthz` com banco no ar | `GET /healthz` | 200 | 200 `{"mensagem":"ok"}` | Passou |
| 10b | `/healthz` com banco pausado | `docker pause`, `GET /healthz` | 503 em cerca de 2 s | 503 em 5,0 s nas chamadas seguintes (medidas 5,03; 5,03; 5,07; 5,01 s). A primeira chamada após a subida não respondeu em 90 s (duas reproduções). Ver achado 1 | Falhou (achado 1) |
| 10c | Volta do banco | `docker unpause`, `GET /healthz` | 200 | 200 em 0,007 s | Passou |
| 11 | Proxy do Vite | `API_PROXY_TARGET=http://127.0.0.1:18080 pnpm --dir web dev --port 15173`; login por `/auth/login` | Login com cookie pelo proxy | Por `localhost:15173`: login 200 com cookie, `/auth/eu` 200, `/healthz` 200, sem cookie 401, logout 204, depois 401. Por `127.0.0.1:15173`: conexão recusada (o Vite escuta só em `[::1]`, ver observação de ambiente) | Passou por `localhost` |

## Verificações adicionais

- **Hash do token:** o SHA-256 do token do cookie confere com o `token_hash` gravado (1 linha). O token em claro não aparece no banco.
- **Logs:** nenhuma ocorrência de senha, token, hash ou de e-mail completo nos logs de login, cadastro, logout e seed. Os logs mostram `email=f***@exemplo.com` e `usuario_id`.
- **Expiração:** com `expira_em` forçado para o passado, `/auth/eu` dá 401.
- **Token adulterado:** 1 caractere trocado, 401.
- **Permissão por parâmetro:** `/auth/eu?id=<outro>` ignora o parâmetro e devolve o usuário da sessão.
- **Método errado:** `GET /auth/login` com sessão dá 405 JSON; sem sessão, 401 (a cadeia responde antes).
- **Papel da aplicação:** conectado como `ballast_app`, sem permissão de DDL.

## Achados

### Bloqueia

Nenhum. Nenhum cenário de autenticação, autorização ou vazamento falhou.

### Corrigir antes de mergear

**1. `/healthz` não responde em 2 s com o banco congelado (trava)**

- **O que acontece:** com o Postgres pausado (`docker pause`), a primeira chamada a `/healthz` após a subida da API não respondeu em 90 s. As chamadas seguintes deram 503 em cerca de 5,0 s, de forma consistente. A espera de 2 s prometida em `application.yml` ("Com 2 s o health check responde logo") e no `setQueryTimeout(2)` de `HealthRepository` não se cumpre. O 503 em si está correto e a volta do banco restaura 200.
- **Como reproduzir:**
  1. Subir a API (perfil dev, porta 18080, base com V1 a V3).
  2. Fazer `GET /healthz` uma vez (200).
  3. `docker pause ballast-qa-pg`
  4. `curl --max-time 90 -w '%{http_code} %{time_total}\n' http://127.0.0.1:18080/healthz`. Na primeira execução do processo, deu 000 em 90 s.
  5. Repetir o passo 4: 503 em cerca de 5 s.
  6. `docker unpause ballast-qa-pg`; `/healthz` volta a 200.
- **O que deveria acontecer:** 503 em cerca de 2 s, nas duas chamadas.
- **Causa:** não confirmada. Hipótese: o `setQueryTimeout` do JDBC cancela por uma conexão nova, e com o servidor congelado esse cancelamento também fica pendurado. Não investiguei além disso.
- **Observado também:** login e `/auth/eu` com o banco pausado respondem 500 em cerca de 5 s, sem travar. Isso é aceitável.

**2. A API aplica as migrations antes de recusar a configuração insegura (erra em silêncio no efeito colateral)**

- **O que acontece:** com `APP_ENV=staging COOKIE_SECURE=false`, a API sai com erro, como o ADR-015 exige, mas só depois que o Flyway aplicou V1 a V3 no banco apontado por `DATABASE_URL`. A base vazia passou de 0 para 5 tabelas (`flyway_schema_history`, `health_check`, `usuario`, `sessao`, `tentativa_login`).
- **Como reproduzir:**
  1. Criar base vazia no Postgres de teste (`DROP DATABASE` e `CREATE DATABASE`).
  2. `DATABASE_URL=... DB_USER=ballast_app DB_PASSWORD=... DB_MIGRATION_USER=qa_admin DB_MIGRATION_PASSWORD=... APP_ENV=staging COOKIE_SECURE=false REGISTRO_ABERTO=false PORT=18080 java -jar api/target/ballast-0.0.1-SNAPSHOT.jar`
  3. Log: "Successfully applied 3 migrations to schema public, now at version v3", seguido de "APPLICATION FAILED TO START ... COOKIE_SECURE=false só é aceito com APP_ENV=development".
  4. Consultar `information_schema.tables` do `public`: 5 tabelas.
- **O que deveria acontecer:** recusar antes de tocar no banco. A validação de `IdentityProperties` roda só quando o `AuthController` é criado, depois do `flywayInitializer`.
- **Impacto:** um `APP_ENV` esquecido em produção migra o banco real antes de falhar. As migrations atuais são aditivas, então o dano imediato é pequeno, mas a ordem contradiz a promessa "a API se recusa a subir".

### Sugestão

**3. Cookie `ballast_sessao` repetido: vale o primeiro (incomoda)**

- **O que acontece:** `Cookie: ballast_sessao=lixo-invalido; ballast_sessao=<token válido>` dá 401. Já `Cookie: ballast_sessao=; ballast_sessao=<token válido>` dá 200, porque o vazio é ignorado. O comportamento depende da ordem.
- **Como reproduzir:** `curl -H "Cookie: ballast_sessao=lixo; ballast_sessao=<token>" http://127.0.0.1:18080/auth/eu` dá 401 com um token válido na segunda posição.
- **O que deveria acontecer:** ou procurar entre os repetidos o que for válido, ou tratar o cookie duplicado de forma uniforme.
- **Risco concreto:** baixo. Um cookie sem `Domain` é host-only, então só quem já injeta cookie no próprio host consegue colocar o `lixo`. Ainda assim é um caminho de derrubar a sessão da vítima.

**Observação de ambiente (não é bug do app):** o Vite 8 escuta só em `[::1]:15173` neste Mac. `http://127.0.0.1:15173` recusa conexão e `http://localhost:15173` funciona. O comando passado para o teste usava `127.0.0.1`, então o teste foi feito por `localhost`.

**Observação de desenho (já documentado na spec):** com `REGISTRO_ABERTO=true`, o cadastro responde 409 para e-mail existente, o que revela quais e-mails têm conta, e não há limite de taxa na rota. O ADR-020 e o `.env.example` já condicionam a abertura a um limite por IP na borda. Registrar isso antes de abrir o cadastro.

**Observação de teste:** a migração desta execução foi rodada pelo superusuário de teste (`qa_admin`, dono do schema). Em produção o ADR-021 pede um papel de migração separado; este teste não cobre essa configuração.

## Resumo

- **Achados:** 3 no total. 0 Bloqueia, 2 Corrigir antes de mergear (achados 1 e 2), 1 Sugestão (achado 3), mais observações de ambiente e de desenho.
- **Os que travam primeiro:** o `/healthz` que não responde em 2 s com o banco congelado (achado 1) e a migração antes da recusa de configuração (achado 2).
- **Recomendação: segurar.** Nada de autenticação, sessão, autorização ou vazamento de segredo falhou, e os 11 cenários de autenticação passam. Mas os dois itens de "Corrigir antes de mergear" são de correção pequena e devem entrar antes do merge. Depois de corrigi-los, reexecutar os cenários 1 e 10. A decisão final é do tech-lead.

**Não cobre:** navegador real e a tela de login (cookie `Secure` sobre HTTPS, Safari), a borda de produção (HTTPS, HSTS, IP real atrás de proxy), limite de taxa no cadastro aberto, expiração natural de 7 dias (testada só forçando a data), carga acima de 10 paralelas e esgotamento do pool do Hikari, e a troca de senha, que ainda não existe.
