.PHONY: setup dev dev-api dev-web build build-api build-web test test-api test-web lint lint-api lint-web db-up db-down migrate

setup:
	pnpm --dir web install --frozen-lockfile

dev:
	$(MAKE) -j2 dev-api dev-web

dev-api:
	cd api && go run ./cmd/api

dev-web:
	pnpm --dir web dev

build: build-api build-web

build-api:
	cd api && go build -o bin/api ./cmd/api

build-web:
	pnpm --dir web build

test: test-api test-web

test-api:
	cd api && go test -race ./...

test-web:
	pnpm --dir web test

lint: lint-api lint-web

lint-api:
	cd api && go vet ./... && golangci-lint run

lint-web:
	pnpm --dir web lint

db-up:
	docker compose up -d db

db-down:
	docker compose down

migrate:
	@test -n "$$DATABASE_URL" || { echo "DATABASE_URL não definida (exporte ou use um .env local)"; exit 1; }
	cd api && go tool goose -dir migrations postgres "$$DATABASE_URL" up
