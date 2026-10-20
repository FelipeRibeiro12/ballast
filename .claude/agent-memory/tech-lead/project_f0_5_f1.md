---
name: project-f0-5-f1
description: Estado após a F0.5 (mergeada, PR #3) e decisões da F1 em andamento (2026-10-03); regras novas para o qa
metadata:
  type: project
---

F0.5 mergeada na main (PR #3). Contrato: `api/openapi.yaml` é a fonte; `docs/contracts/api.md` só aponta. Tipos TS em `web/src/api/schema.d.ts` (gerado, commitado). Gate no `ci.yml`, job web, antes do build. Changelog em `CHANGELOG.md` (fatia entregue sai do backlog).

Armadilhas:
- Gate do cliente TS só falha se o **tipo gerado** mudar: `required` sem propriedade correspondente deixa o web verde (o job api pega pelo teste de aderência).
- Postgres 17 não tem `uuidv7()` (é do 18): UUID v7 gerado na aplicação, sem dependência (decisão minha; google/uuid exigiria aprovação).
- `git revert` de várias commits: conferir a lista antes; revertei README sem querer uma vez (desfeito com reset local antes do push).
- `sed -i` do macOS (BSD) falha silenciosamente em cadeia com `&&`; usar python para editar.

F1 (branch feat/f1-cadastro-login): ADR-015 (token opaco, SHA-256 no banco, cookie HttpOnly/Secure/SameSite=Lax; JWT descartado). Tabelas usuario/sessao/tentativa_login (chave = e-mail, sem FK, para não enumerar conta). Sem `mfa_segredo` (F20). `REGISTRO_ABERTO=false` por padrão, 404 quando desligado, seed do usuário. Rate limit na borda é a F20.5 e é ela que destrava `REGISTRO_ABERTO=true`. Sem tela de cadastro. `health_check` segue como dívida (fora da F1).

Ajustes do usuário na F1 (2026-10-03, depois do backend já lançado): UUID v7 via `github.com/google/uuid` (aprovada; à mão só com 3 testes: versão 7, variante 10, monotonicidade com N goroutines); 404 do cadastro fica (403 revelaria que existe), spec documenta 201 e 404 e o teste de aderência roda os dois estados de REGISTRO_ABERTO; `COOKIE_SECURE` padrão true, `.env.example` de dev com false, e a API **recusa subir** com false fora de `APP_ENV` de desenvolvimento (dívida: HTTPS local com mkcert, aposentar a flag); `schema.d.ts` regenerado no mesmo commit que muda o `openapi.yaml` (exceção autorizada à fronteira: backend roda `pnpm --dir web generate:api`), todo commit verde por causa do bisect.

Regras do usuário para o qa (2026-10-03):
- Se o qa travar, **parar e descrever onde empacou**, não assumir a tarefa. O qa não tem Edit de propósito. (Na F0.5 ele travou 2x ao corrigir o relatório e eu editei sozinho: o usuário não gostou da lógica; causa provável: sem Edit ele reescreve o arquivo inteiro via Write.)
- Todo relatório de QA inclui uma linha do que os testes **não** cobrem.

**Why:** suíte verde sem limites declarados vira confiança falsa; fronteira de papel é deliberada.
**How to apply:** ao delegar ao qa e ao ver um agente travar.

## Estado da F1 (2026-10-04, branch feat/f1-cadastro-login, sem push)

Backend A+B, frontend, QA (2 rodadas + correção de narrativa), docs e security feitos. Achados de segurança: M1 (corrida do atraso) corrigido com reserva atômica (b932606); M2 (DoS pelo 429 por e-mail) pendente de o usuário aceitar o risco, com ADR em nome dele; M3/B3 viram requisito da F20.5 antes do primeiro deploy público. Falta: `/pr` (usuário invoca), reviewer, merge, fechar a F1 (tirar do backlog para o CHANGELOG).

Armadilhas desta rodada:
- QA (Haiku) reproduziu "bug" que não existia por usar a porta 8080 (servidor antigo); sempre exigir porta alta e o pid do próprio processo. Reler relatório do QA contra o código antes de aceitar achado.
- `DATABASE_URL` não persiste entre chamadas de Bash: teste de integração pula em silêncio e parece verde.
- Subagente já despejou o ambiente do shell no output uma vez (`export $(cat ...)`): proibir no prompt.
- Em subagentes, `Write` do arquivo inteiro funcionou para o QA quando o relatório foi pedido uma vez só; travou ao tentar corrigir linhas pontuais.

## Migração para Java (2026-10-19, PR #4, branch feat/migracao-java)

Stack virou Java 25 + Boot 4.1.1 (ADR-016 a 019); F1 em Go descartada, volta como fatia. Diário em `docs/diario.md`.

Armadilhas:
- JDK 25 é keg-only no Homebrew: todo comando precisa de `JAVA_HOME=/opt/homebrew/opt/openjdk@25`. Docker Desktop pode estar parado: `open -a Docker`.
- Ruleset "main protegida" (id 24421437) exige 7 checks pelo nome. Renomear job: tirar o nome antigo via `gh api -X PUT .../rulesets/24421437`, empurrar, incluir o novo depois do primeiro run. Merge não exige aprovação (0), mas `strict` exige branch atualizada.
- `gh pr checks --watch` sai cedo com checks pendentes; usar laço com `grep pending`.
- osv-scanner mostra "found 14 packages" (diretas) mas resolve transitivas; já pegou CVE do Tomcat e Jackson gerenciados pelo Boot.
- Subagente pode afirmar arquivo desatualizado sem ter lido o atual (docs disse que o CLAUDE.md citava Go): conferir com grep antes de aceitar.

## F1 em Java (2026-10-19, PR #5)

- O check `CodeQL` (resultados do code scanning) não é obrigatório no ruleset, mas falha com alerta aberto; ler os alertas antes do merge (`gh api repos/.../code-scanning/alerts?ref=refs/pull/N/merge`).
- QA com a API real pegou o que os testes de MockMvc não pegavam (banco congelado com `docker pause`); manter esse passo.
- Porta 8080: o processo `api` antigo saiu; agora o Docker Desktop escuta em `*:8080` sem container, e a API ainda sobe em 127.0.0.1:8080. QA e testes de proxy seguem em porta alta.

## F1.1 tela de cadastro (2026-10-19, PR #6)

- `make seed` com "url not specified" era `DATABASE_URL` vazio no `.env` do dono. Diagnosticar o `.env` só por nome de chave e formato (awk com comprimento, espaço, `@`), nunca pelo valor.
- Volume do Postgres anterior à ADR-021 não tem `ballast_owner` nem `ballast_app`: o init só roda com volume vazio, então é `docker compose down -v`.
- `VITE_*` do `.env` da raiz chega ao Vite pelo `export` do Makefile (conferido no módulo servido). O reviewer disse o contrário sem testar.

## F2 contas (2026-10-19, PR #7)

- QA contra a API real com Postgres descartável próprio (porta alta) pegou o B1 que a suíte verde não pegava: banco congelado logo depois de uma requisição. Manter o QA real em toda fatia com rota de banco.
- Rodar `qa` em paralelo com o `frontend` funciona: não se tocam. Pedir ao `backend` a causa com evidência antes da correção.
- Fatia entregue sai do backlog no PR seguinte se esquecer; conferir o `## MVP` ao fechar.

## F2 a F10 (2026-10-19 e 20, PRs #7 a #16)

- Padrão que funcionou por fatia: ADR primeiro, dba (migration) e backend em paralelo quando não se tocam, frontend e qa em paralelo, reviewer e security em paralelo com o CI. Merge só depois do CI do último commit (ruleset strict).
- QA contra a API real com Postgres descartável e oráculo independente pegou bug em quase toda fatia que a suíte verde não pegava (trava, teto, ciclo de trava do job, janela de pagamento). Manter.
- Ninguém abria o web de verdade: o proxy do Vite ficou sem as rotas de dados da F2 à F8 (PR #14). Agora há teste que compara o proxy com o openapi.yaml; a conferência em navegador continua sendo dívida do dono.
- Contrato que muda o tipo consumido pelo web quebra o build do web: commitar API e o ajuste mínimo do web juntos.
- A API do make dev do dono roda na 8080; agentes usam portas altas e nunca a derrubam. O computador dormir mata agente no meio: conferir container e processo antes de retomar.
