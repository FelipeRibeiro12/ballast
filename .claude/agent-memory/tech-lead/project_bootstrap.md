---
name: project-bootstrap
description: Onde as coisas ficam, armadilhas e dívidas do bootstrap do monorepo (2026-10-03); commits locais, aguardando push do usuário
metadata:
  type: project
---

Estado em 2026-10-03: commits locais, sem push (o usuário faz o push e a branch protection): `fe6d717` config inicial, `0f44fa9` api e web, `b6f3d47` fix (127.0.0.1, ci, pt-BR), `333acbb` docs (CLAUDE.md + docs/), `59c1957` .claude (settings, hook, rules), depois a memória dos agentes e o título da página. api/ em Go 1.27.1 só stdlib, web/ React 19 + Vite 8, compose Postgres 17.

Decisões:
- Lint do web é **oxlint**, `react/exhaustive-deps` em `error`. Não trocar por ESLint.
- `golangci-lint` v2.14.0+ só por **brew**, nunca `go install` (versão diverge do CI; v2.14.0 é a primeira que linta Go 1.27). CI fixa `version: v2.14.0`.
- `ci.yml` = lint, teste, build, push só na main + pull_request. `seguranca.yml` = gitleaks, govulncheck, pnpm audit, CodeQL. Não misturar.
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

Dívidas abertas: actions por SHA + Dependabot (registrada no backlog, "Mais adiante"); `govulncheck@latest` não reproduzível; só `ReadHeaderTimeout` no servidor (falta Read/Write/IdleTimeout quando houver rota real); `<title>` do `web/index.html` ainda "web"; tabela do `TestHealthHandler` com um caso só; workflows nunca rodaram no GitHub (pnpm 12.8.1 e Node 26 nas actions não confirmados).

**Why:** evita refazer investigação e perder dívida.
**How to apply:** ao mexer em CI, lockfile, lint, backlog ou commits; ao abrir o `/sdlc-skeleton`.
