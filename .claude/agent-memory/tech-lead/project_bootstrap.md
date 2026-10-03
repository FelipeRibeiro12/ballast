---
name: project-bootstrap
description: Onde as coisas ficam, armadilhas e dívidas do bootstrap do monorepo (2026-10-03); commits locais, aguardando push do usuário
metadata:
  type: project
---

Estado em 2026-10-03: publicado em github.com/FelipeRibeiro12/ballast (público, `main`), os dois workflows verdes. O usuário subiu até `59c1957` por conta própria; o resto foi com `git push` (o repo já existia, então `gh repo create` não se aplicava). Branch protection ainda é com o usuário, no painel. api/ em Go 1.27.1 só stdlib, web/ React 19 + Vite 8, compose Postgres 17.

Decisões:
- Lint do web, golangci-lint e separação dos workflows: ver ADR-010 a 012.
- Gatilho do `ci.yml`: push só na main + `pull_request` (senão roda duas vezes em PR).
- API escuta em `127.0.0.1` por padrão (`HOST`); container/produção precisa de `HOST=0.0.0.0` explícito.
- PWA é a F9 do backlog (vite-plugin-pwa só entra nela, `devOptions.enabled: false`, porque service worker cacheia asset no dev). Numeração atual: Cartão F10, cotação F14, MFA F20, Python F27.
- `web/package.json` tem `packageManager: pnpm@12.8.1` e o lockfile tem `packageManagerDependencies` (pnpm 12 exige). `pnpm/action-setup@v4` precisa de `package_json_file: web/package.json`.
- Compose sem senha padrão, porta só em 127.0.0.1. Não existe `make migrate` (falta escolher goose ou golang-migrate).
- Correções de arquivo já commitado vão em commit novo, nunca reescrever histórico antes do push.

Armadilhas:
- `SendMessage` está desabilitado: abrir subagente novo com instrução completa.
- `CLAUDE.md`, `docs/`, `.claude/` nunca tinham sido commitados (não eram ignorados, só nunca adicionados). Sempre `git add` com caminhos explícitos.
- `.claude/agent-memory/` é versionado (decisão do usuário em 2026-10-03): passa por revisão como qualquer arquivo. Não pôr segredo nem dado pessoal ali.
- Comentários em português precisam de acento (preferência do usuário).

Dívidas abertas: actions por SHA + Dependabot (registrada no backlog, "Mais adiante"); `govulncheck@latest` não reproduzível; só `ReadHeaderTimeout` no servidor (falta Read/Write/IdleTimeout quando houver rota real); tabela do `TestHealthHandler` com um caso só; runner fixado em `ubuntu-24.04` em 2026-10-03 (lembrete datado no backlog: conferir a migração para a 26 depois de 2026-11).

Avisos dos runs, **vistos e dispensados** (não reabrir a cada run): "Node 20 deprecado" nas actions é o runtime delas, não nosso; "Restore cache failed: go.sum not found" some sozinho quando a api tiver a primeira dependência.

**Why:** evita refazer investigação e perder dívida.
**How to apply:** ao mexer em CI, lockfile, lint, backlog ou commits; ao abrir o `/sdlc-skeleton`.

## Skeleton (2026-10-03, branch feat/skeleton, 9 commits, ainda sem push)

- Rota é `/healthz` (não `/health`). Lê `health_check` (descartável, ver dívida no backlog). pgx nativo (ADR-013), goose como `tool` no go.mod (ADR-014); `make migrate` usa `go tool goose`.
- Makefile faz `-include .env`: `.env` não aceita aspas, `$` nem `#`. Volume do compose sobrevive a `db-down`; senha antiga dá "password authentication failed" → `docker compose down -v`.
- Teste de integração: sem `DATABASE_URL` pula localmente, mas `t.Fatal` se `CI` definida. CI tem service `postgres:17` com senha descartável duplicada (service + URL), que o gitleaks pode flagrar.
- Pendente: o CI vermelho só se prova depois do push (commit quebrado descartável no PR, depois reverter). QA local: `reports/qa/skeleton.md`, sem bloqueador.
- Fatia fecha com `/pr` (usuário invoca); primeiro PR contra a main protegida.
