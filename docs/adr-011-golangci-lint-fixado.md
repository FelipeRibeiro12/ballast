# ADR-011: golangci-lint instalado por gerenciador de pacotes e fixado por versão

**Status:** aceito
**Data:** 2026-10-03

## Contexto

O lint do Go roda na máquina e no CI. Se as versões divergem, o lint reprova só num dos dois.

## Decisão

`brew install golangci-lint` na máquina, e `version: v2.14.0` fixada na action do CI.

## Justificativa

A v2.14.0 é a primeira que linta Go 1.27. Abaixo dela o lint falha com erro confuso sobre carregar pacote. `go install @latest` faz a versão divergir entre máquina e CI.

## Consequência

A regra vale para qualquer ferramenta de build: versão fixada e igual na máquina e no CI. É o motivo do `packageManager` no `web/package.json`.

## Alternativas descartadas

- **`go install`** — a versão instalada depende do dia em que foi rodado.
- **Deixar a action escolher a versão** — o CI passa a mudar sem mudança de código nossa.
