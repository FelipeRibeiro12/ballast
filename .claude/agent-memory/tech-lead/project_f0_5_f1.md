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
