---
paths:
  - "api/**/*.sql"
  - "api/migrations/**"
---

# Banco de dados

## Migration

- **Migration aplicada não se edita.** Escreva uma nova. Editar uma antiga
  quebra o histórico de quem já rodou, e o erro só aparece na máquina do outro.
- Todo `up` tem um `down`. Se a ferramenta não suporta, escreva no comentário
  como desfazer.
- Nome diz o que faz: `cria_remessas`, não `update_table`.
- Uma migration, uma mudança lógica.

## Segurança em tabela com dado

Adicionar coluna `NOT NULL` sem default quebra em tabela populada. O caminho é
em passos separados:

1. adiciona a coluna nullable
2. preenche
3. torna obrigatória

O mesmo vale para renomear (adiciona nova → copia → passa a usar → remove a
antiga) e para mudar tipo.

Antes de qualquer operação destrutiva — drop de coluna ou tabela, mudança de
tipo que perde dado — **pare e avise**. Não execute por iniciativa própria.

## Schema

- Chave primária em toda tabela.
- Chave estrangeira com ação de delete explícita: `RESTRICT` por padrão,
  `CASCADE` só quando o filho não existe sem o pai.
- `NOT NULL` é o padrão. Nullable precisa de motivo: nulo é "desconhecido", não
  "vazio".
- Dinheiro em inteiro (centavos) ou decimal exato. **Nunca float.**
- Data e hora sempre com timezone, sempre UTC no banco.
- Unicidade que o negócio exige vira `UNIQUE` no banco. Validar só na aplicação
  não segura duas requisições simultâneas.
- `created_at` e `updated_at` em tabela de entidade.

## Índice

Crie quando existe query real sofrendo, não por precaução: todo índice custa
escrita e espaço.

- Chave estrangeira usada em `JOIN` ou filtro quase sempre merece.
- Índice composto serve na ordem em que as colunas aparecem no filtro.
- Mostre `EXPLAIN ANALYZE` antes e depois. Sem o plano, é chute.
- Em tabela grande no Postgres, `CREATE INDEX CONCURRENTLY` para não travar
  escrita.

## Query

- **Sempre parametrizada.** Concatenar string é injeção esperando acontecer.
- Colunas nomeadas, não `SELECT *`, em código de produção.
- Query dentro de laço é quase sempre `N+1`.
- Paginação por cursor quando a lista cresce. `OFFSET` grande fica lento.
- `DELETE` e `UPDATE` sem `WHERE` não existem. Confira duas vezes.
