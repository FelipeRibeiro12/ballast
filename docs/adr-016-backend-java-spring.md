# ADR-016: Java 25 com Spring Boot 4 no backend

**Status:** aceito
**Data:** 2026-10-19
**Substitui:** ADR-002 (Go)

## Contexto

O ADR-002 escolheu Go por afinidade com o ecossistema de blockchain, que era a
direção de carreira do dono do projeto. Essa direção mudou: o objetivo passou a
ser empregabilidade no mercado brasileiro, onde Java com Spring é a stack de
backend mais pedida.

O projeto está na F1. O backend em Go é um `/healthz` e metade da
autenticação, então este é o momento mais barato para trocar.

## Decisão

**Java 25 (LTS) com Spring Boot 4**, monolito modular, mantendo o ADR-001.
Build com Maven e wrapper (`mvnw`) commitado.

Java 17 é só o piso do Spring Boot 4. O projeto nasce na LTS atual.

## Justificativa

**O motivo é mercado e oportunidade de trabalho**, decisão do dono do projeto.
Fica escrito assim porque é a razão verdadeira.

Há consequências técnicas favoráveis, e elas são consequência, não motivo:

- **`BigDecimal` é da plataforma.** Fiat e cripto passam a ter a mesma
  representação na aplicação (emenda do ADR-005).
- **`java.time`** resolve na biblioteca padrão o que a F5 mais precisa: fim de
  mês, dia 31 em fevereiro, ano bissexto, `YearMonth`. O ADR-002 já registrava
  que em Go essa regra seria escrita à mão.

## Alternativas descartadas

- **Manter Go**: foi a escolha do ADR-002 por afinidade com blockchain, e essa
  razão deixou de valer com a mudança de objetivo.

## Consequência

- **A implementação Go da F1 é descartada; as decisões dela, não.** A migration
  de `usuario`, `sessao` e `tentativa_login` porta para o Flyway (ADR-018), e o
  ADR-015 (token opaco, SHA-256 no banco, cookie) continua valendo. Muda só a
  implementação.
- ADR-013 (pgx) e ADR-014 (goose) são substituídos pelo ADR-017 e pelo
  ADR-018. O ADR-011 (golangci-lint) é descartado sem substituto.
- O ADR-005 é emendado com as regras de `BigDecimal`; o contrato do JSON não
  muda. O ADR-009 é emendado: o cliente do indexador fica no monolito.
- A spec OpenAPI passa a ser gerada do código (ADR-019).
- **Os nomes dos jobs do CI ficam os mesmos, com uma exceção.** O ruleset da
  `main` exige sete checks pelo nome. Job renomeado sem mexer no ruleset vira
  check obrigatório que nunca roda, e nenhum PR merge mais. Muda o conteúdo
  do job, não o nome. No CodeQL a linguagem vira `java-kotlin` e o job mantém
  o nome.
- **A exceção é "análise estática (go)", que vira "análise estática (api)".**
  Nome de job descreve o módulo, não a linguagem, senão quebra em toda troca
  de stack. A troca acontece no PR da migração, nesta ordem: tirar o nome
  antigo dos checks obrigatórios do ruleset, renomear o job e empurrar, e
  depois do primeiro run incluir o nome novo como obrigatório.

## Limitação reconhecida

O tipo compartilhado com o frontend continua ausente, como no ADR-002. A
mitigação muda de forma no ADR-019.
