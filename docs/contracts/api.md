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
- Em qualquer rota, banco fora responde 503 `{"erro":"serviço indisponível"}`, e
  corpo acima de 4 KiB responde 413 (ADR-023).
- Em `/contas/{id}`, conta de outro usuário, id inexistente e id que não é UUID
  respondem 404 com o mesmo corpo.
- Dinheiro de fiat sai e entra como inteiro de centavos
  (`saldoInicialCentavos`, `saldoCentavos`), entre -9007199254740991 e
  9007199254740991. Decimal no JSON (`12.5`) é recusado com 400.
