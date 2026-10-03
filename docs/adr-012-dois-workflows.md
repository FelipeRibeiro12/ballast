# ADR-012: dois workflows separados por responsabilidade

**Status:** aceito
**Data:** 2026-10-03

## Contexto

O pipeline precisa de lint, teste e build, e também de varredura de segurança.

## Decisão

`ci.yml` com lint, teste e build. `seguranca.yml` com gitleaks, govulncheck, `pnpm audit` e CodeQL. Auditoria de dependência não entra no `ci.yml`, e teste não entra no `seguranca.yml`.

## Justificativa

O tempo de CI não é duplicado, e fica claro qual gate falhou.

## Consequência

A branch protection da `main` precisa marcar os checks dos dois workflows, não de um só.

## Alternativas descartadas

- **Workflow único** — mais simples de configurar e pior de ler quando quebra.
