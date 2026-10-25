#!/usr/bin/env bash
# Roda na VM, chamado pelo workflow de deploy por SSH: carrega a imagem que chega pela entrada
# padrão, sobe com a tag nova e espera o /healthz. Falhou, volta para a tag anterior.
set -euo pipefail

tag="${1:?uso: deploy.sh <tag> < imagem.tar.gz}"
cd /opt/ballast

# Segredo vazio faria o matcher do Caddyfile deixar passar requisição direta na VM.
segredo="$(sed -n 's/^ORIGIN_SECRET=//p' caddy.env)"
if [ "${#segredo}" -lt 32 ]; then
  echo "ORIGIN_SECRET em caddy.env precisa de pelo menos 32 caracteres" >&2
  exit 1
fi

gunzip | docker load

anterior="$(cat .api_tag 2>/dev/null || true)"
echo "$tag" > .api_tag
# Exportada porque todo docker compose relê o compose.yml, e o exec do reload e do wget também
# precisa da tag para interpolar a imagem.
export API_TAG="$tag"
docker compose up -d --remove-orphans
# O Caddyfile chega por scp e é montado no contêiner: sem o reload o Caddy segue com o antigo.
docker compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile

# O Caddy alcança a API pela rede do compose; 30 tentativas de 2 s cobrem a subida da JVM.
for _ in $(seq 1 30); do
  if docker compose exec -T caddy wget -q -O /dev/null http://api:8080/healthz; then
    echo "deploy ok: $tag"
    exit 0
  fi
  sleep 2
done

echo "a API não respondeu o /healthz com $tag" >&2
if [ -n "$anterior" ]; then
  echo "$anterior" > .api_tag
  export API_TAG="$anterior"
  docker compose up -d --remove-orphans
  echo "voltou para $anterior" >&2
fi
exit 1
