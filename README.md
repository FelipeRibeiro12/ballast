# Ballast

App de controle financeiro pessoal: fiat e cripto no mesmo lugar, com recorrência, cartão de crédito parcelado, metas e projeção de saldo.

Hoje a API (Java 25, Spring Boot 4) responde só `GET /healthz`, que confere a conexão com o Postgres. A web mostra o resultado.

Visão do produto em `docs/visao.md`. Decisões em `docs/adr-*.md`.

## Pré-requisitos

- JDK 25, com `JAVA_HOME` apontando para ele
- Docker com Docker Compose (Postgres local e Testcontainers nos testes)
- Node 26 (versão usada no CI) e pnpm 12.8.1 (campo `packageManager` do `web/package.json`)

Maven não precisa estar instalado: a API usa o wrapper `api/mvnw`.

## Como rodar

No macOS, o OpenJDK do Homebrew é keg-only:

```bash
brew install openjdk@25
export JAVA_HOME="$(brew --prefix openjdk@25)"   # fish: set -gx JAVA_HOME (brew --prefix openjdk@25)
cp .env.example .env                              # depois preencha o .env
make setup
make db-up
make dev
```

Preencha `.env` no formato `CHAVE=valor`, sem aspas, `$` ou `#`:

- `DATABASE_URL`: `jdbc:postgresql://localhost:5432/<POSTGRES_DB>`
- `DB_USER` e `DB_PASSWORD`: papel que a API usa, só com DML (ADR-021). Escolha valores **diferentes** de `POSTGRES_USER`; o Compose cria o papel no init do volume novo.
- `DB_MIGRATION_USER` e `DB_MIGRATION_PASSWORD`: dono do schema, usado só pelo Flyway. No dev, repita `POSTGRES_USER` e `POSTGRES_PASSWORD`.
- `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`: o Compose usa para criar o banco na primeira subida.
- `HOST` (padrão `127.0.0.1`) e `PORT` (padrão `8080`): onde a API escuta.

`COINGECKO_API_URL` está no `.env.example`, mas ainda não é usada.

As migrations rodam sozinhas quando a API sobe, pelo Flyway. Não há comando separado.

- API: `http://127.0.0.1:8080/healthz`. Responde 503 quando o banco não responde.
- Web: `http://localhost:5173` (porta padrão do Vite).

`make dev` sobe API e web juntas. `make dev-api` e `make dev-web` sobem cada uma.

O proxy do Vite (`web/vite.config.ts`) repassa `/healthz` para `http://localhost:8080` fixo. Se mudar `PORT`, mude também o proxy.

### Se você rodou a versão em Go

O volume do Postgres local tem tabelas criadas pelo goose e nenhum histórico do Flyway. O Flyway recusa subir nesse estado. O `.env` antigo também não serve: `DATABASE_URL` estava no formato `postgres://` e faltam `DB_USER` e `DB_PASSWORD`.

Para recomeçar, apagando os dados locais de desenvolvimento:

```bash
docker compose down -v
cp .env.example .env   # sobrescreve o .env antigo; preencha de novo
make db-up
```

## Testar e construir

```bash
make test    # testes da API (sobem o Postgres com Testcontainers) e da web
make lint    # spotless na API e oxlint na web
make build   # jar da API e build de produção da web
```

`make test` precisa do Docker rodando e de `make setup` feito antes. Não precisa de `.env` nem de banco subido.

## Contrato da API

`api/openapi.yaml` é gerado do código pelo springdoc e commitado (ADR-019). O teste `OpenApiSpecTest`, dentro do `make test`, falha se o arquivo commitado diverge do gerado.

Ao mudar rota ou DTO:

```bash
make spec
git add api/openapi.yaml web/src/api/schema.d.ts
```

`make spec` regenera a spec e o cliente TypeScript.

## Estrutura

```
api/
├── mvnw                           # wrapper do Maven
├── openapi.yaml                   # contrato gerado do código
└── src/main/
    ├── java/com/felipe/ballast/   # um pacote por contexto; hoje: health, openapi
    └── resources/db/migration/    # migrations Flyway (V<n>__descricao.sql)
web/
└── src/                           # React + Vite
docs/                              # visão, ADRs, contratos, backlog
```
