# Contrato da API

A fonte da verdade é [`api/openapi.yaml`](../../api/openapi.yaml). Este arquivo
não repete a spec: mudou rota, corpo ou status, muda a spec, no mesmo PR da
implementação.

- O teste de aderência do Go valida as respostas dos handlers contra a spec.
- O cliente TS é gerado da spec no build do web; o CI falha se o arquivo
  commitado divergir do gerado.
