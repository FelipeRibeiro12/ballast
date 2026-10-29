# ADR-064: CRUD de categoria

**Status:** aceito
**Data:** 2026-10-28

## Contexto

Só havia as 16 sementes. O dono quer criar, renomear e apagar categorias. O
Posso gastar deixa de olhar categoria (ADR-063), então Salário não precisa
mais ser protegida.

## Decisão

- Rotas: `POST /categorias` (nome e tipo), `PUT /categorias/{id}` (só o nome) e
  `DELETE /categorias/{id}`. O tipo não muda depois de criado.
- Cada usuário tem até 20 categorias, sem contar **Outros** e **Outras
  receitas**, que são protegidas: não renomeiam nem apagam (409). O teto é o
  `UserLimit.CATEGORIES` (ADR-055, `LIMITE_CATEGORIAS`). As 14 sementes comuns
  contam, então um usuário antigo começa com 6 vagas.
- Só criamos categoria raiz. A coluna `pai_id` fica no banco, e categoria com
  filha não se apaga (409).
- Apagar move lançamentos e recorrências para a Outros (despesa) ou Outras
  receitas (receita), numa transação, e soma o teto de orçamento na Outros no
  mesmo mês de vigência. Se a Outros já tinha teto no mês, os dois somam.
- Categoria listada em conta de benefício não se apaga (409): tirá-la da lista
  em silêncio podia deixar a conta aceitando qualquer categoria.
- Nome repetido, sem diferenciar caixa, é 409.
- A resposta ganha `protegida`.
- A marca `salario` continua no banco, mas nada a protege.

## Consequência

Apagar uma categoria muda o histórico dos relatórios: o gasto passa a aparecer
em Outros. É o que o dono pediu.

## Revisão de 2026-10-29

Se um lançamento entra na categoria entre a mudança dos lançamentos e o DELETE,
a FK recusa o DELETE e a rota responde 409 "a categoria mudou enquanto era
apagada", sem apagar nada. Antes era 500.
