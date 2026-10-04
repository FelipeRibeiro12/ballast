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

Preencha em `.env`:
- `POSTGRES_USER`: usuário do banco (ex.: `postgres`)
- `POSTGRES_PASSWORD`: senha (sem aspas, `$` ou `#`)
- `POSTGRES_DB`: nome do banco (ex.: `ballast`)
- `DATABASE_URL`: string de conexão (ex.: `postgres://postgres:suasenha@localhost:5432/ballast`); caracteres especiais na senha (`@`, `/`, `:`) quebram a URL — use apenas letras e números
- `SEED_EMAIL`, `SEED_SENHA`, `SEED_NOME`: dados do primeiro usuário (senha de 12 a 128 caracteres)
- `APP_ENV=development` e `COOKIE_SECURE=false`: desenvolvimento local (Safari recusa cookie Secure sobre HTTP)

As demais chaves já têm valor no `.env.example`. `COINGECKO_API_URL` não é usada ainda.

A chave `.env` real nunca entra no repositório.

Instale dependências, suba o banco e aplique migrations:

```bash
make setup
make db-up
make migrate
```

Se `make migrate` falhar com "password authentication failed", o banco já existiu com outra senha. Limpe com `docker compose down -v` e repita `make db-up` e `make migrate`.

## Rodar

Depois de `make setup` e `make migrate`, crie o primeiro usuário:

```bash
make seed
make dev
```

Abra `http://localhost:5173` e entre com `SEED_EMAIL` e `SEED_SENHA`.

A API sobe em `http://localhost:8080/healthz`, web em `http://localhost:5173`. `/healthz` confirma que o banco está respondendo.

Por padrão, a API escuta só em 127.0.0.1. Em container ou produção, defina `HOST=0.0.0.0` no `.env`.

Para rodar separado: `make dev-api` (só backend) ou `make dev-web` (só frontend).

O proxy do Vite repassa `/auth` e `/healthz` para a API; mude o alvo com `API_PROXY_TARGET` (padrão `http://localhost:8080`).

## Autenticação

Rotas: `POST /auth/cadastro` (só com `REGISTRO_ABERTO=true`), `POST /auth/login`, `POST /auth/logout`, `GET /auth/eu`. O contrato está em `api/openapi.yaml`.

Após três falhas consecutivas de senha no mesmo e-mail, a API responde 429 com `Retry-After` e o atraso dobra a cada falha, até 15 minutos. Não bloqueia a conta.

## Testar e construir

```bash
make test    # testes de API e web
make lint    # linter de Go (go vet, golangci-lint) e web (oxlint)
make build   # binário da API e build de produção da web
```

Comandos por pacote: `make test-api`, `make test-web`, `make build-api`, `make build-web`.

Os testes de integração do Go precisam de `DATABASE_URL` definida.

## Contrato da API

A spec OpenAPI está em `api/openapi.yaml`. Os tipos TypeScript são gerados para `web/src/api/schema.d.ts`.

Quando você altera a spec:

```bash
pnpm --dir web generate:api
git add api/openapi.yaml web/src/api/schema.d.ts
```

O `pnpm build` (web) já executa `generate:api` antes de compilar. O CI falha se o arquivo gerado divergir do commitado. O teste do Go (`api/internal/http/openapi_test.go`) valida que toda rota do router consta na spec, e vice-versa.

Rota nova entra na spec no mesmo PR da implementação.

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
