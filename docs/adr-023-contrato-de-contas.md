# ADR-023: Contrato de contas na F2

**Status:** aceito
**Data:** 2026-10-19

## Contexto

A F2 cria, lista e arquiva contas. O modelo em `docs/contracts/dados.md`
descreve a tabela `conta` inteira, com seis tipos e moeda, mas lançamento,
cartão, benefício e cripto só chegam em fatias depois. Faltava fixar o que a
F2 aceita, como o JSON carrega o valor e como a rota muda o estado.

## Decisão

- **Nomes.** Tabela `conta`, em português como `usuario` e `sessao`. Pacote
  Java `accounts`, em inglês como `identity`.
- **Tipos.** A F2 aceita só `corrente`, `poupanca` e `dinheiro`, e o `CHECK`
  do banco também. Cada fatia que trouxer outro tipo (`beneficio`,
  `cartao_credito`, `cripto`) alarga o `CHECK` por migration, junto com a
  tabela de apoio que o tipo exige.
- **Moeda.** Sempre `BRL` na F2. A coluna existe com `DEFAULT 'BRL'` e um
  `CHECK (moeda = 'BRL')`; a requisição não tem o campo, a resposta tem.
- **Nome.** De 1 a 100 caracteres depois de tirar espaço das pontas, sem
  caractere de controle. Único por usuário **sem diferenciar caixa**
  (`unique (usuario_id, lower(nome))`), inclusive contra conta arquivada.
  Repetido dá 409.
- **Valor no JSON.** `saldoInicialCentavos` e `saldoCentavos`, inteiros em
  centavos (ADR-005). A API recusa com 400 valor fora de
  ±9.007.199.254.740.991 (`Number.MAX_SAFE_INTEGER`), porque acima disso o
  JavaScript perde o centavo. Negativo é aceito (cheque especial).
- **Saldo.** Calculado em SQL pelo `JdbcClient` (ADR-017). Na F2 é o
  `saldo_inicial`, porque não existe lançamento; a F3 soma os realizados na
  mesma consulta. Nunca coluna.
- **Rotas.**
  - `POST /contas` cria e devolve 201.
  - `GET /contas` lista as ativas. Com `?arquivadas=true`, lista só as
    arquivadas.
  - `GET /contas/{id}` devolve uma.
  - `PATCH /contas/{id}` com `{"arquivada": true|false}` arquiva ou
    desarquiva. Corpo JSON, para seguir a regra do ADR-020 de que rota que
    muda estado só aceita `application/json`.
  - Conta de outro usuário, ou id que não existe, dá 404 nas duas rotas com
    id.
- **Limite de corpo.** O filtro de 4 KiB passa a valer em toda rota, não só
  em `/auth/**`. Upload de extrato (F22) vai precisar de uma exceção própria.
- **Banco fora.** Falha de conexão com o banco vira 503 `serviço
  indisponível` em qualquer rota, igual ao `/healthz`. Fecha a dívida da F1.

## Justificativa

Aceitar tipo que ainda não tem a tabela de apoio criaria conta de cartão sem
fechamento ou conta de benefício sem regra de categoria. Alargar o `CHECK`
depois custa uma linha de migration.

Nome sem diferenciar caixa evita "Nubank" e "nubank" lado a lado na lista, que
é sempre erro de digitação.

`PATCH` com o campo explícito deixa arquivar e desarquivar idempotentes: mandar
duas vezes dá o mesmo estado.

## Alternativas descartadas

- **`CHECK` com os seis tipos já agora**: segue o modelo-alvo, mas deixa o
  banco aceitar o que a aplicação ainda não sabe tratar.
- **`POST /contas/{id}/arquivar` sem corpo**: seria a segunda exceção à regra
  do `application/json`, depois do logout.
- **`DELETE` para arquivar**: arquivar não apaga, e o verbo mentiria.

## Consequência

`docs/contracts/dados.md` passa a registrar o índice único por `lower(nome)`
no lugar de `unique (usuario_id, nome)`, e o `CHECK` de tipo e de moeda da
F2.
