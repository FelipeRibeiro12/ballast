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
- Em `/lancamentos/{id}`, vale a mesma regra de 404 (outro usuário, inexistente
  ou id que não é UUID, mesmo corpo). `valorCentavos` é sempre positivo, de 1
  a 9007199254740991, e o sinal vem do `tipo` (ADR-024).
- `GET /lancamentos` exige `de` e `ate` (AAAA-MM-DD, ano de 1900 a 2199, `de`
  menor ou igual a `ate`, no máximo 366 dias contando os dois extremos) e filtra
  por `dataCaixa`. `contaId` opcional: UUID malformado dá 400; UUID bem formado
  de conta alheia ou inexistente dá lista vazia, para não revelar a conta.
- Em `POST` e `PUT /lancamentos`, conta inexistente, de outro usuário ou id
  inválido dá 400 `conta não encontrada`; categoria inexistente, de outro
  usuário, de tipo diferente do lançamento ou id inválido dá 400
  `categoria não encontrada`; conta arquivada dá 400 `conta arquivada`. No `PUT`,
  a recusa por conta arquivada vale só ao trocar de conta: editar ou apagar
  lançamento que já está numa conta arquivada é permitido.
- O corpo do lançamento recebe uma `data` e a API grava a mesma em
  `dataCompetencia` e `dataCaixa`. Campo não declarado dá 400.
