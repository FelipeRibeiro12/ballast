# Ballast

App de controle financeiro pessoal: fiat e cripto no mesmo lugar, com recorrência, cartão de crédito parcelado, metas e projeção de saldo. Leia a visão do produto em `docs/visao.md` e as decisões de arquitetura em `docs/adr-*.md`.

## Pré-requisitos

- Go 1.27.1
- Node 26
- pnpm 12.8.1 (presente no `web/package.json`)
- Docker com Docker Compose
- golangci-lint v2.14.0 ou superior (`brew install golangci-lint`; confirme com `golangci-lint --version`)

## Setup

Copie o arquivo de variáveis de ambiente:

```bash
cp .env.example .env
```

Preencha em `.env` as quatro chaves obrigatórias:
- `POSTGRES_USER`: usuário do banco (ex.: `postgres`)
- `POSTGRES_PASSWORD`: senha (sem aspas, `$` ou `#`)
- `POSTGRES_DB`: nome do banco (ex.: `ballast`)
- `DATABASE_URL`: string de conexão (ex.: `postgres://postgres:suasenha@localhost:5432/ballast`); caracteres especiais na senha (`@`, `/`, `:`) quebram a URL — use apenas letras e números

As chaves `SESSION_SECRET` e `COINGECKO_API_URL` não são usadas ainda e podem ficar vazias. `HOST` e `PORT` já têm valor no `.env.example`.

A chave `.env` real nunca entra no repositório.

Instale dependências, suba o banco e aplique migrations:

```bash
make setup
make db-up
make migrate
```

Se `make migrate` falhar com "password authentication failed", o banco já existiu com outra senha. Limpe com `docker compose down -v` e repita `make db-up` e `make migrate`.

## Rodar

```bash
make dev
```

API sobe em `http://localhost:8080/healthz`, web em `http://localhost:5173`. `/healthz` confirma que o banco está respondendo.

Por padrão, a API escuta só em 127.0.0.1. Em container ou produção, defina `HOST=0.0.0.0` no `.env`.

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
