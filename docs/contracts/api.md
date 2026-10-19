# Contrato da API

A spec é [`api/openapi.yaml`](../../api/openapi.yaml), gerada do código pelo
springdoc e commitada (ADR-019). Este arquivo não repete a spec: mudou rota,
corpo ou status no código, a spec regenerada entra no mesmo commit.

- O CI falha se a spec commitada divergir da gerada do código.
- O cliente TS é gerado da spec no build do web; o CI falha se o arquivo
  commitado divergir do gerado.
- As rotas declaram `produces = application/json`. Cliente que manda só
  `Accept: text/html` recebe 406, e isso vale também para o `/healthz`: probe
  de orquestrador precisa aceitar JSON ou `*/*`.
