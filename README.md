# Ballast

App de controle financeiro pessoal: fiat e cripto no mesmo lugar, com recorrência, cartão de crédito parcelado, metas e projeção de saldo.

Hoje existem cadastro, login, logout e a sessão que os sustenta. Contas, lançamentos e os demais módulos ainda não existem.

Visão do produto em `docs/visao.md`. Decisões em `docs/adr-*.md`.

## Pré-requisitos

- JDK 25, com `JAVA_HOME` apontando para ele
- Docker com Docker Compose (Postgres local e Testcontainers nos testes)
- Node 26 (versão usada no CI) e pnpm 12.8.1 (campo `packageManager` do `web/package.json`)

Maven não precisa estar instalado: a API usa o wrapper `api/mvnw`.

## Como rodar

No macOS, o OpenJDK do Homebrew é keg-only:

```bash
git clone <url-do-repositorio> ballast && cd ballast
brew install openjdk@25
export JAVA_HOME="$(brew --prefix openjdk@25)"   # fish: set -gx JAVA_HOME (brew --prefix openjdk@25)
cp .env.example .env                              # preencha o .env antes do passo seguinte
make setup
make db-up
make seed
make dev
```

Abra `http://localhost:5173` e entre com `SEED_EMAIL` e `SEED_SENHA`. A API responde em `http://127.0.0.1:8080/healthz`.

- `make dev` sobe API e web juntas. `make dev-api` e `make dev-web` sobem cada uma.
- `make seed` cria o usuário inicial e aplica as migrations. Se o usuário já existe, não faz nada.
- Use `localhost` para abrir a web, não `127.0.0.1`. O Vite pode escutar só em `[::1]`, e aí `127.0.0.1:5173` recusa a conexão.
- As migrations rodam sozinhas quando a API sobe, pelo Flyway.

### Variáveis do `.env`

Preencha no formato `CHAVE=valor`, sem aspas, `$` ou `#`. O modelo está em `.env.example`.

| Variável | Para que serve |
|---|---|
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/<POSTGRES_DB>` |
| `DB_USER`, `DB_PASSWORD` | Papel que a API usa. Só tem `SELECT`, `INSERT`, `UPDATE` e `DELETE` (ADR-021). **Tem que ser diferente de `POSTGRES_USER`**: o init do Compose faz `CREATE ROLE` com esse nome, e o nome de `POSTGRES_USER` já existe. |
| `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | Dono do schema, usado só pelo Flyway. No dev, repita `POSTGRES_USER` e `POSTGRES_PASSWORD`. |
| `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` | Usados pelo Compose na primeira subida do volume. |
| `HOST`, `PORT` | Onde a API escuta. Padrão `127.0.0.1` e `8080`. |
| `APP_ENV` | Só `development` libera `COOKIE_SECURE=false`. Vazio ou ausente conta como produção. |
| `COOKIE_SECURE` | Vazio vale `true`. `false` só com `APP_ENV=development`; com outro `APP_ENV` a API não sobe. |
| `REGISTRO_ABERTO` | `false` por padrão. `true` libera `POST /auth/cadastro`. Deixe `false` enquanto não houver limite de requisições na borda. |
| `VITE_REGISTRO_ABERTO` | Vazio por padrão. `true` mostra a tela de cadastro no web. Só funciona junto com `REGISTRO_ABERTO=true` (ADR-022). O `make dev` repassa a do `.env` da raiz; rodando `pnpm dev` direto, ponha em `web/.env`. |
| `SEED_EMAIL`, `SEED_SENHA`, `SEED_NOME` | Usuário criado por `make seed`. Senha de 12 a 128 caracteres. Nome é opcional, até 100 caracteres. |
| `API_DOCS_ENABLED` | `true` serve `/v3/api-docs`. Use só em desenvolvimento. Qualquer outro valor deixa desligado. |
| `COINGECKO_API_URL` | Está no `.env.example`, mas ainda não é usada. |

`COOKIE_SECURE=false` existe porque o Safari não aceita cookie `Secure` em HTTP. O fluxo com cookie `Secure` em `http://localhost` não foi conferido em navegador real: foi visto só por curl e jsdom.

### Proxy da web

O Vite repassa `/healthz` e `/auth` para a API. O destino padrão é `http://localhost:8080`. Se a API estiver em outra porta ou endereço, suba a web com `API_PROXY_TARGET`, por exemplo `API_PROXY_TARGET=http://127.0.0.1:8080 make dev-web`. Com o padrão, o Node tenta `::1` e cai para `127.0.0.1`, onde a API escuta.

### Volume do Postgres de antes desta versão

O init do Compose roda só em volume novo. Um volume criado antes desta versão não tem o papel `DB_USER`, e a V3 do Flyway falha ao conceder DML para ele. Um volume da versão em Go tem tabelas do goose, e o Flyway recusa subir sobre elas. Nos dois casos, recomece. Isso apaga os dados locais de desenvolvimento:

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

Rotas atuais:

- `GET /healthz`: público. Responde 503 quando o banco não responde.
- `POST /auth/login`: corpo `{"email", "senha"}`. Abre a sessão no cookie `ballast_sessao`, válido por 7 dias sem renovação. Depois de 3 falhas seguidas com o mesmo e-mail, cada nova falha espera o dobro do tempo anterior (2 s, 4 s, 8 s...), com teto de 15 min. A resposta é 429 com `Retry-After`. O atraso vale com ou sem conta. Tentativa durante o atraso não é verificada nem contada.
- `GET /auth/eu`: devolve o usuário da sessão.
- `POST /auth/logout`: revoga a sessão no servidor e apaga o cookie. Responde 204.
- `POST /auth/cadastro`: existe só com `REGISTRO_ABERTO=true`. Fechado, responde 401 sem sessão e 404 com sessão, como uma rota inexistente.

Rota que não é pública responde 401 sem sessão. `POST` exige `Content-Type: application/json`.

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
    ├── java/com/felipe/ballast/   # um pacote por contexto: health, identity, security, web, openapi
    └── resources/db/migration/    # migrations Flyway (V<n>__descricao.sql)
db/init/                           # script de init do Postgres: cria o papel DB_USER
web/
└── src/                           # React + Vite
docs/                              # visão, ADRs, contratos, backlog
reports/                           # relatórios de QA e segurança por fatia
```

## Links

- `docs/visao.md`: produto
- `docs/backlog.md`: fatias e dívidas
- `docs/seguranca.md` e `docs/ameacas.md`: segurança
- `docs/contracts/`: modelo de dados e contrato da API
- `docs/adr-015`, `docs/adr-020`, `docs/adr-021`: sessão, Spring Security e usuários de banco
- `CHANGELOG.md`: o que já foi entregue
