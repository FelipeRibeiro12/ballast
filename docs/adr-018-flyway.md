# ADR-018: Flyway para migrations

**Status:** aceito
**Data:** 2026-10-19
**Substitui:** ADR-014 (goose)

## Contexto

O ADR-014 escolheu goose, declarado como tool no `go.mod` para a versão ser a
mesma na máquina e no CI. Sem Go (ADR-016), a ferramenta precisa de substituta.
As migrations existentes são SQL puro, então muda o executor e o nome do
arquivo, não o schema.

## Decisão

**Flyway**, com SQL puro em `api/src/main/resources/db/migration`, arquivo
`V<n>__descricao.sql`. Roda na subida da aplicação, pela integração do Spring
Boot. A versão vem do `pom.xml`, igual na máquina e no CI.

As migrations do goose portam renomeando: `00002_cria_usuario_sessao_tentativa_login.sql`
vira `V2__cria_usuario_sessao_tentativa_login.sql`.

## Justificativa

Flyway é SQL-first, que é como as migrations deste projeto já são escritas, e
o Spring Boot integra sem configuração extra.

## Alternativas descartadas

- **Liquibase**: changelog em XML ou YAML além de SQL e suporte melhor a
  vários bancos. Com um Postgres só e migrations em SQL puro, isso é cerimônia
  sem ganho.

## Consequência

- **O bloco `-- +goose Down` não pode ir junto.** O Flyway ignora a anotação e
  executa o arquivo inteiro, então criaria as tabelas e as apagaria em
  seguida. O `Down` vira comentário com o SQL que desfaz a migration, como o
  `.claude/rules/sql.md` já exige quando a ferramenta não tem `down`.
- Migration aplicada não se edita: escreva outra. Mudança destrutiva em passos
  separados (nullable → preenche → obrigatória). Nada disso muda.
- O `paths` da rule `sql.md` (`api/**/*.sql`) já cobre o diretório novo.
