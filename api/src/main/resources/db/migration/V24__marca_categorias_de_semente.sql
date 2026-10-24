-- ADR-057. As categorias de semente que o código procura (Salário no Posso gastar, Outros e
-- Outras receitas ao apagar conta) passam a ser marcadas por coluna, e não achadas pelo nome.
-- Renomear ou apagar uma categoria deixa de quebrar a busca. A marca só vale na raiz: filha de
-- Salário continua contando como salário pela relação com a raiz marcada.
ALTER TABLE categoria ADD COLUMN semente text;

-- O backfill por nome só existe aqui. Raiz com o nome e o tipo da semente recebe a marca; o que o
-- usuário tiver criado com outro nome fica sem ela.
UPDATE categoria SET semente = 'salario'
    WHERE pai_id IS NULL AND nome = 'Salário' AND tipo = 'receita';
UPDATE categoria SET semente = 'outros'
    WHERE pai_id IS NULL AND nome = 'Outros' AND tipo = 'despesa';
UPDATE categoria SET semente = 'outras_receitas'
    WHERE pai_id IS NULL AND nome = 'Outras receitas' AND tipo = 'receita';

ALTER TABLE categoria ADD CONSTRAINT categoria_semente_valida
    CHECK (semente IS NULL OR semente IN ('salario', 'outros', 'outras_receitas'));
-- Outros é a única semente de despesa.
ALTER TABLE categoria ADD CONSTRAINT categoria_semente_tipo
    CHECK (semente IS NULL OR ((semente = 'outros') = (tipo = 'despesa')));
ALTER TABLE categoria ADD CONSTRAINT categoria_semente_so_na_raiz
    CHECK (semente IS NULL OR pai_id IS NULL);

-- Uma marca de cada tipo por usuário. Parcial para as categorias comuns (semente nula) não
-- entrarem no índice. Também atende a busca pela marca (usuario_id, semente).
CREATE UNIQUE INDEX categoria_semente_unica
    ON categoria (usuario_id, semente) WHERE semente IS NOT NULL;

-- Para desfazer (o Flyway Community não tem down; rode à mão). Perde a marca; o código novo
-- deixa de achar as sementes.
-- DROP INDEX categoria_semente_unica;
-- ALTER TABLE categoria DROP CONSTRAINT categoria_semente_so_na_raiz;
-- ALTER TABLE categoria DROP CONSTRAINT categoria_semente_tipo;
-- ALTER TABLE categoria DROP CONSTRAINT categoria_semente_valida;
-- ALTER TABLE categoria DROP COLUMN semente;
