# Em make, a atribuição do arquivo vence o ambiente; no CI não há .env, então o
# ambiente do workflow segue valendo. Formato KEY=valor sem aspas, sem $ e sem #.
# O mvnw usa o JAVA_HOME do ambiente (JDK 25); o Makefile não fixa caminho.
-include .env
export

.PHONY: setup dev dev-api dev-web build build-api build-web test test-api test-web lint lint-api lint-web db-up db-down spec seed e2e

setup:
	pnpm --dir web install --frozen-lockfile

dev:
	$(MAKE) -j2 dev-api dev-web

dev-api:
	cd api && ./mvnw spring-boot:run

dev-web:
	pnpm --dir web dev

build: build-api build-web

build-api:
	cd api && ./mvnw -B -DskipTests package

build-web:
	pnpm --dir web build

test: test-api test-web

test-api:
	cd api && ./mvnw -B verify

test-web:
	pnpm --dir web test

lint: lint-api lint-web

lint-api:
	cd api && ./mvnw -B spotless:check

lint-web:
	pnpm --dir web lint

db-up:
	docker compose up -d db

db-down:
	docker compose down

# Regenera api/openapi.yaml a partir do código e o cliente TS a partir dela (ADR-019).
spec:
	cd api && ./mvnw -B test -Dtest=OpenApiSpecTest -Dspec.atualizar=true
	pnpm --dir web generate:api

# Cria o usuário inicial de SEED_EMAIL, SEED_SENHA e SEED_NOME (.env). Idempotente; sobe sem
# servidor web e sai.
seed:
	cd api && SPRING_PROFILES_ACTIVE=seed ./mvnw -B -q spring-boot:run

# Verificação em navegador (ADR-033): Postgres, API e web descartáveis em portas altas.
# Precisa de Docker e, na primeira vez, de `pnpm --dir web exec playwright install chromium`.
e2e:
	./scripts/e2e.sh
