# ADR-017: Spring Data JPA, com SQL direto para agregação

**Status:** aceito
**Data:** 2026-10-19
**Substitui:** ADR-013 (pgx)

## Contexto

Com a troca para Java (ADR-016), o acesso ao Postgres precisa de decisão nova.
O ADR-013 escolhera o pgx nativo pelo suporte a `numeric`. Em Java,
`BigDecimal` mapeia para `numeric` sem perda em qualquer opção, então o
critério passa a ser o tipo de consulta.

## Decisão

**Spring Data JPA** para CRUD de agregado. **`JdbcClient` ou query nativa**
para agregação: saldo, saldo projetado, fluxo de caixa e gráficos.

`spring.jpa.hibernate.ddl-auto` fica em **`validate`**, nunca `update`.

## Justificativa

JPA é o que o mercado usa, coerente com o motivo do ADR-016, e cobre bem o
CRUD.

As consultas que este app mais precisa acertar são agregações sobre
`lancamento` filtrando por `usuario_id` e por data, somando por categoria ou
conta. Em JPQL isso fica mais difícil de ler que o SQL e esconde o plano de
execução, que é o que se olha antes de criar índice.

`validate` faz o Hibernate conferir na subida que as entidades batem com o
schema que o Flyway construiu, e a aplicação não sobe se divergir. `update`
alteraria o schema sozinho, sem migration e sem registro.

## Alternativas descartadas

- **JPA para tudo, inclusive agregação**: JPQL de soma e agrupamento custa
  mais que o SQL e o plano de execução some de vista.
- **jOOQ**: SQL tipado, forte justamente em agregação. Descartado por ter
  presença bem menor em vaga que JPA, o que vai contra o motivo do ADR-016.

## Consequência

Três armadilhas de JPA aparecem caladas e caras, e estão em
`.claude/stacks/java-spring.md`:

- `FetchType.LAZY` explícito em todo `@ManyToOne` e `@OneToOne`: o padrão é
  `EAGER`.
- N+1 em listagem: `JOIN FETCH` ou `@EntityGraph`.
- Entidade JPA nunca sai na API. Entidade é persistência, DTO é contrato.
