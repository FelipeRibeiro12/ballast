#!/usr/bin/env bash
# Verificação em navegador (ADR-033): sobe Postgres, API e web descartáveis em portas altas,
# roda o Playwright e derruba tudo, passe ou falhe. Não lê o .env e não toca em 8080, 5173
# nem no container do `make dev`.
set -euo pipefail

raiz="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
container="ballast-e2e"
tmp="$(mktemp -d)"
pids=()

# Descendentes primeiro: o `pnpm exec` e o `vite` deixam processos filhos que o kill do pai não pega.
matar_arvore() {
  local filho
  for filho in $(pgrep -P "$1" 2>/dev/null || true); do matar_arvore "$filho"; done
  kill "$1" 2>/dev/null || true
}

limpar() {
  local codigo=$?
  trap - EXIT INT TERM
  local pid
  for pid in ${pids[@]+"${pids[@]}"}; do matar_arvore "$pid"; done
  docker rm -f -v "$container" >/dev/null 2>&1 || true
  if [ "$codigo" -ne 0 ] && [ -f "$tmp/api.log" ]; then
    echo "--- fim do log da API ---" >&2
    tail -n 40 "$tmp/api.log" >&2 || true
  fi
  rm -rf "$tmp"
  exit "$codigo"
}
trap limpar EXIT INT TERM

# Porta livre pedida ao sistema. O intervalo efêmero fica bem acima de 8080 e 5173.
porta_livre() {
  node -e "const s=require('net').createServer();s.listen(0,'127.0.0.1',()=>{console.log(s.address().port);s.close()})"
}

esperar() {
  local descricao="$1" tentativas="$2"
  shift 2
  local i
  for ((i = 0; i < tentativas; i++)); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "Tempo esgotado esperando: $descricao" >&2
  return 1
}

# Senhas só existem nesta execução e nunca são impressas.
senha_dono="$(openssl rand -hex 16)"
senha_app="$(openssl rand -hex 16)"
porta_db="$(porta_livre)"
porta_api="$(porta_livre)"
porta_web="$(porta_livre)"

docker rm -f -v "$container" >/dev/null 2>&1 || true
echo "Subindo o Postgres (porta $porta_db)"
# O init cria o papel da aplicação, o mesmo script do compose (ADR-021).
docker run -d --name "$container" \
  -e POSTGRES_USER=ballast_dono -e POSTGRES_PASSWORD="$senha_dono" -e POSTGRES_DB=ballast_e2e \
  -e DB_USER=ballast_app -e DB_PASSWORD="$senha_app" \
  -p "127.0.0.1:$porta_db:5432" \
  -v "$raiz/db/init:/docker-entrypoint-initdb.d:ro" \
  postgres:17 >/dev/null
# -h 127.0.0.1: durante o init o servidor temporário só escuta no socket, então o teste por
# TCP só passa quando o servidor definitivo sobe.
esperar "Postgres" 60 docker exec "$container" pg_isready -h 127.0.0.1 -U ballast_dono -d ballast_e2e

echo "Construindo a API"
(cd "$raiz/api" && ./mvnw -B -q -DskipTests package)
jar="$(find "$raiz/api/target" -maxdepth 1 -name 'ballast-*.jar' ! -name '*.original' | head -n 1)"
[ -n "$jar" ] || { echo "Jar da API não encontrado em api/target" >&2; exit 1; }

echo "Subindo a API (porta $porta_api)"
# env -i: variáveis exportadas pelo Makefile a partir do .env (DB_USER, PORT...) não vazam.
env -i PATH="$PATH" HOME="$HOME" ${JAVA_HOME:+JAVA_HOME="$JAVA_HOME"} \
  DATABASE_URL="jdbc:postgresql://127.0.0.1:$porta_db/ballast_e2e" \
  DB_USER=ballast_app DB_PASSWORD="$senha_app" \
  DB_MIGRATION_USER=ballast_dono DB_MIGRATION_PASSWORD="$senha_dono" \
  REGISTRO_ABERTO=true COOKIE_SECURE=false APP_ENV=development \
  BALLAST_RECURRENCE_JOB_ENABLED=false BALLAST_COTACOES_JOB_ENABLED=false \
  HOST=127.0.0.1 PORT="$porta_api" \
  java -jar "$jar" >"$tmp/api.log" 2>&1 &
pids+=("$!")
esperar "API em /healthz" 90 curl -fsS "http://127.0.0.1:$porta_api/healthz"

echo "Construindo o web"
# O build vai para fora de web/dist para não pisar no build do dono. A flag de cadastro é de
# build (ADR-022).
VITE_REGISTRO_ABERTO=true pnpm --dir "$raiz/web" exec vite build --outDir "$tmp/web" --emptyOutDir >/dev/null

echo "Subindo o web (porta $porta_web)"
# O preview herda o server.proxy do vite.config.ts, então a lista de rotas é a mesma do dev.
API_PROXY_TARGET="http://127.0.0.1:$porta_api" \
  pnpm --dir "$raiz/web" exec vite preview --outDir "$tmp/web" --host 127.0.0.1 --port "$porta_web" --strictPort \
  >"$tmp/web.log" 2>&1 &
pids+=("$!")
esperar "web" 30 curl -fsS "http://127.0.0.1:$porta_web/"
# Confere o proxy de ponta a ponta antes de gastar tempo com o navegador.
esperar "proxy do web até a API" 5 curl -fsS "http://127.0.0.1:$porta_web/healthz"

echo "Rodando o Playwright"
rm -rf "$raiz/web/e2e/capturas"
mkdir -p "$raiz/web/e2e/capturas"
E2E_BASE_URL="http://127.0.0.1:$porta_web" pnpm --dir "$raiz/web" exec playwright test
