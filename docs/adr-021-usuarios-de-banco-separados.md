# ADR-021: Usuário de banco da migração separado do usuário da aplicação

**Status:** aceito
**Data:** 2026-10-19

## Contexto

O Flyway roda na subida da aplicação (ADR-018). Com um usuário só, a conta que
atende as requisições também pode `DROP TABLE`. A auditoria do PR da migração
(M1) apontou isso contra a regra de menor privilégio do `seguranca.md`: uma
injeção de SQL futura deixaria de vazar dado e passaria a destruir o
histórico financeiro.

## Decisão

- O Flyway conecta como **dono do schema**: `DB_MIGRATION_USER` e
  `DB_MIGRATION_PASSWORD` (`spring.flyway.user` e `password`).
- A aplicação conecta como `DB_USER` e `DB_PASSWORD`, com **só DML**:
  `SELECT`, `INSERT`, `UPDATE`, `DELETE`.
- Uma migration concede DML nas tabelas existentes e, por `ALTER DEFAULT
  PRIVILEGES`, nas futuras, ao papel informado pelo placeholder do Flyway
  `${app_user}`.
- O papel da aplicação é criado **fora** da migration, porque criar papel com
  senha exige superusuário: no desenvolvimento, por script de init do
  compose; nos testes, pelo init do container; em produção, na configuração da
  hospedagem, que ainda está em aberto.

## Justificativa

A separação custa duas variáveis e uma migration agora. Depois de haver dado
real, trocar o usuário da aplicação exige mexer em grant de produção.

## Alternativas descartadas

- **Um usuário só**: o estado anterior, que é o problema.
- **Migration como passo separado do deploy**: tira o DDL da aplicação por
  completo, mas exige um pipeline de deploy que ainda não existe. Fica como
  evolução natural quando a hospedagem for escolhida.

## Consequência

Os testes de integração precisam conectar a aplicação com o usuário de DML,
senão a separação não está provada.
