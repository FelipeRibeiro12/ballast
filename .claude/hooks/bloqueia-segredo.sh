#!/bin/bash
# Bloqueia escrita em arquivos de segredo. A regra em CLAUDE.md é um pedido;
# este hook é a garantia. Exit 2 impede a ação e devolve a mensagem ao Claude.

INPUT=$(cat)
CAMINHO=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

if [ -z "$CAMINHO" ]; then
  exit 0
fi

BASE=$(basename "$CAMINHO")

case "$BASE" in
  .env|.env.*|*.key|*.pem|secrets.json|*.keystore)
    echo "Bloqueado: $BASE guarda segredo e nao entra no repositorio." >&2
    exit 2
    ;;
esac

exit 0
