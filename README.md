# Ballast

App de controle financeiro pessoal: fiat e cripto no mesmo lugar, com recorrência, cartão de crédito parcelado, metas e projeção de saldo. Leia a visão do produto em `docs/visao.md` e as decisões de arquitetura em `docs/adr-*.md`.

## Pré-requisitos

- Go 1.27.1
- Node 26
- pnpm 12.8.1 (presente no `web/package.json`)
- Docker com Docker Compose
- golangci-lint v2.14.0 ou superior (`brew install golangci-lint`; confirme com `golangci-lint --version`)

## Setup

Copie o exemplo de variáveis:

```bash
cp .env.example .env
```

Preencha em `.env`: `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`, `DATABASE_URL`, `SESSION_SECRET` e `COINGECKO_API_URL`. A senha não tem valor padrão propositalmente. Arquivo `.env` nunca é commitado.

Instale dependências e suba banco:

```bash
make setup
make db-up
```

## Rodar

```bash
make dev
```

API sobe em `http://localhost:8080/healthz`. Web em `http://localhost:5173`.

Por padrão, a API escuta só em 127.0.0.1. Em container ou produção, defina `HOST=0.0.0.0`.

Para rodar separado: `make dev-api` (só backend) ou `make dev-web` (só frontend).

## Testar e construir

```bash
make test    # testes de API e web
make lint    # linter de Go (go vet, golangci-lint) e web (oxlint)
make build   # binário da API e build de produção da web
```

Comandos por pacote: `make test-api`, `make test-web`, `make build-api`, `make build-web`.

## Estrutura

```
api/
├── cmd/api/       # binário
├── internal/      # pacotes privados (contas, lançamentos, recorrência, cartão, etc)
└── migrations/    # esquema do banco
web/
└── src/           # React + Vite
docs/             # visão, ADRs, contratos
```

Pacotes não importam internals de outros pacotes — a comunicação é por interface definida no consumidor.
