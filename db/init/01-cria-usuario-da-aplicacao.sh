#!/bin/sh
# Roda só em volume novo (docker compose down -v). Cria o papel da aplicação sem
# nenhum privilégio; a V3 do Flyway concede o DML. A senha vem do ambiente do container.
set -eu

psql -v ON_ERROR_STOP=1 -v app_user="$DB_USER" -v app_password="$DB_PASSWORD" \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE :"app_user" LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE;
SQL
