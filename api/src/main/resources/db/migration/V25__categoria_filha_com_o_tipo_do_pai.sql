-- ADR-057. Categoria filha tem o mesmo tipo do pai, garantido por FK composta como nas outras
-- (sem gatilho). Antes a regra era só convenção: uma filha de despesa sob pai de receita fazia o
-- orçamento e os gráficos por categoria somarem no grupo errado.

-- Dado existente que a regra nova recusaria: a filha vira raiz, mantendo tipo e lançamentos. Se
-- já houver raiz com o mesmo nome (sem diferenciar caixa), ganha o sufixo " (raiz)" para não
-- colidir com categoria_usuario_pai_nome_unico. Nenhuma rota cria filha hoje, então o esperado
-- é não tocar linha nenhuma.
UPDATE categoria f
SET pai_id = NULL,
    nome = CASE WHEN EXISTS (SELECT 1 FROM categoria r
                             WHERE r.usuario_id = f.usuario_id AND r.pai_id IS NULL
                               AND lower(r.nome) = lower(f.nome))
                THEN left(f.nome, 93) || ' (raiz)' ELSE f.nome END
FROM categoria p
WHERE f.pai_id = p.id AND f.usuario_id = p.usuario_id AND f.tipo <> p.tipo;

-- pai_tipo é o tipo da própria linha quando ela tem pai, nulo na raiz (a FK é MATCH SIMPLE e não
-- checa com coluna nula). Aponta para o unique (id, usuario_id, tipo) que a V6 criou para a FK
-- de lancamento. Mudar o tipo de um pai que tem filha, ou o da filha, é recusado. A aplicação não
-- escreve na coluna gerada. A reescrita de categoria é de milissegundos: são poucas linhas.
ALTER TABLE categoria ADD COLUMN pai_tipo text
    GENERATED ALWAYS AS (CASE WHEN pai_id IS NOT NULL THEN tipo END) STORED;
ALTER TABLE categoria ADD CONSTRAINT categoria_pai_mesmo_tipo
    FOREIGN KEY (pai_id, usuario_id, pai_tipo)
    REFERENCES categoria (id, usuario_id, tipo) ON DELETE NO ACTION;

-- Para desfazer (o Flyway Community não tem down; rode à mão). A promoção a raiz não se reverte.
-- ALTER TABLE categoria DROP CONSTRAINT categoria_pai_mesmo_tipo;
-- ALTER TABLE categoria DROP COLUMN pai_tipo;
