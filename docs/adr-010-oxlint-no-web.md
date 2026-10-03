# ADR-010: oxlint no lugar de ESLint no web

**Status:** aceito
**Data:** 2026-10-03

## Contexto

O template do Vite trouxe o oxlint. ESLint é o padrão histórico do ecossistema.

## Decisão

Manter o oxlint do template, com `react/rules-of-hooks` e `react/exhaustive-deps` como `error`.

## Justificativa

Trocar de linter no primeiro dia é trabalho sem retorno. A regra que importa, dependência de hook como erro, já está atendida.

## Alternativas descartadas

- **ESLint** — tem mais plugins disponíveis. Migrar depois é possível, porque a configuração de lint não contamina o código.
