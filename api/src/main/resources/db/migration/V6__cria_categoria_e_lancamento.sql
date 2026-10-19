-- ADR-024. Ids sem DEFAULT: o UUID v7 é gerado na aplicação, como nas outras tabelas.
-- O dono é garantido pelo banco: lancamento aponta para conta e categoria por FK composta
-- com usuario_id, então nem um bug na aplicação grava lançamento com recurso de outro usuário.

-- Alvo da FK composta de lancamento. A chave primária já é única, mas a FK precisa de uma
-- restrição que cubra exatamente (id, usuario_id).
ALTER TABLE conta ADD CONSTRAINT conta_id_usuario_unico UNIQUE (id, usuario_id);

-- CASCADE em usuario_id: a exclusão de usuário apaga tudo (docs/ameacas.md).
-- Todas as FKs abaixo são NO ACTION (o padrão, escrito por extenso), e não RESTRICT. As duas
-- recusam apagar o pai com filho, mas o RESTRICT checa na hora, no meio do cascade do usuario,
-- e travaria a exclusão do usuário se a conta fosse apagada antes dos lançamentos. O NO ACTION
-- checa no fim do comando, quando o cascade já limpou os filhos.
-- cor e icone são opcionais (nulo = sem escolha). Tamanho curto: cor cabe '#RRGGBBAA' e icone
-- cabe um nome de ícone, ambos escolhidos pelo frontend.
CREATE TABLE categoria (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    nome text NOT NULL,
    tipo text NOT NULL,
    cor text,
    icone text,
    pai_id uuid,
    -- Colunas geradas que garantem a hierarquia de um nível sem trigger. nivel é 0 para raiz e
    -- 1 para filha. pai_nivel é sempre 0 quando há pai: a FK de pai exige que o pai tenha nivel 0,
    -- então uma filha nunca vira pai. A aplicação não escreve nelas.
    nivel smallint GENERATED ALWAYS AS (CASE WHEN pai_id IS NULL THEN 0 ELSE 1 END) STORED NOT NULL,
    pai_nivel smallint GENERATED ALWAYS AS (CASE WHEN pai_id IS NULL THEN NULL ELSE 0 END) STORED,
    CONSTRAINT categoria_tipo_valido CHECK (tipo IN ('receita', 'despesa')),
    CONSTRAINT categoria_nome_tamanho CHECK (char_length(nome) BETWEEN 1 AND 100),
    CONSTRAINT categoria_nome_sem_espaco_nas_pontas CHECK (nome = btrim(nome)),
    CONSTRAINT categoria_cor_tamanho CHECK (char_length(cor) BETWEEN 1 AND 9),
    CONSTRAINT categoria_icone_tamanho CHECK (char_length(icone) BETWEEN 1 AND 50),
    -- Alvo da FK de lancamento, que carrega o tipo para despesa não apontar para categoria de receita.
    CONSTRAINT categoria_id_usuario_tipo_unico UNIQUE (id, usuario_id, tipo),
    -- Alvo da FK de pai, que exige pai do mesmo usuário e de nivel 0.
    CONSTRAINT categoria_id_usuario_nivel_unico UNIQUE (id, usuario_id, nivel),
    CONSTRAINT categoria_pai_mesmo_usuario_e_raiz FOREIGN KEY (pai_id, usuario_id, pai_nivel)
        REFERENCES categoria (id, usuario_id, nivel) ON DELETE NO ACTION
);

-- NULLS NOT DISTINCT (Postgres 15+) faz duas raízes de mesmo nome colidirem. Num unique comum,
-- pai_id nulo nunca colide e o mesmo nome entraria duas vezes na raiz. A alternativa,
-- COALESCE(pai_id, uuid sentinela), obriga a repetir a expressão em toda consulta.
-- Também atende a busca por usuario_id (prefixo do índice).
CREATE UNIQUE INDEX categoria_usuario_pai_nome_unico
    ON categoria (usuario_id, pai_id, lower(nome)) NULLS NOT DISTINCT;

-- Sementes dos usuários que já existem. Usuários novos recebem a mesma lista pela aplicação
-- (docs/contracts/dados.md), então as duas listas têm de andar juntas. gen_random_uuid() é v4:
-- serve para backfill, a aplicação gera v7 nas categorias novas. O DISTINCT protege de nome
-- repetido na lista.
INSERT INTO categoria (id, usuario_id, nome, tipo)
SELECT gen_random_uuid(), u.id, s.nome, s.tipo
FROM usuario u
CROSS JOIN (
    SELECT DISTINCT nome, tipo
    FROM (VALUES
        ('Salário', 'receita'),
        ('Aluguel recebido', 'receita'),
        ('Benefício', 'receita'),
        ('Rendimento', 'receita'),
        ('Outras receitas', 'receita'),
        ('Alimentação', 'despesa'),
        ('Refeição', 'despesa'),
        ('Mercado', 'despesa'),
        ('Combustível', 'despesa'),
        ('Transporte', 'despesa'),
        ('Moradia', 'despesa'),
        ('Saúde', 'despesa'),
        ('Lazer', 'despesa'),
        ('Assinatura', 'despesa'),
        ('Educação', 'despesa'),
        ('Outros', 'despesa')
    ) AS lista (nome, tipo)
) s;

-- valor é sempre positivo, o sinal vem do tipo. O teto é o Number.MAX_SAFE_INTEGER em centavos.
-- tipo aceita só receita e despesa até a F4 (transferencia), e status só realizado até a
-- recorrência (previsto, cancelado). Cada fatia alarga o CHECK por migration nova.
-- Ano entre 1900 e 2199 nas duas datas, para data absurda não passar.
-- As FKs de conta e categoria são NO ACTION pelo motivo do cascade explicado acima. Hoje conta
-- só é arquivada, e categoria não é apagada: a recusa de apagar com lançamento é a rede de segurança.
CREATE TABLE lancamento (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    tipo text NOT NULL,
    titulo text NOT NULL,
    descricao text,
    categoria_id uuid NOT NULL,
    conta_id uuid NOT NULL,
    valor numeric(18, 2) NOT NULL,
    data_competencia date NOT NULL,
    data_caixa date NOT NULL,
    status text NOT NULL DEFAULT 'realizado',
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT lancamento_tipo_valido CHECK (tipo IN ('receita', 'despesa')),
    CONSTRAINT lancamento_status_valido CHECK (status IN ('realizado')),
    CONSTRAINT lancamento_titulo_tamanho CHECK (char_length(titulo) BETWEEN 1 AND 100),
    CONSTRAINT lancamento_titulo_sem_espaco_nas_pontas CHECK (titulo = btrim(titulo)),
    CONSTRAINT lancamento_descricao_tamanho CHECK (char_length(descricao) <= 500),
    CONSTRAINT lancamento_valor_faixa CHECK (valor > 0 AND valor <= 90071992547409.91),
    CONSTRAINT lancamento_data_competencia_faixa
        CHECK (data_competencia BETWEEN DATE '1900-01-01' AND DATE '2199-12-31'),
    CONSTRAINT lancamento_data_caixa_faixa
        CHECK (data_caixa BETWEEN DATE '1900-01-01' AND DATE '2199-12-31'),
    CONSTRAINT lancamento_conta_mesmo_usuario FOREIGN KEY (conta_id, usuario_id)
        REFERENCES conta (id, usuario_id) ON DELETE NO ACTION,
    CONSTRAINT lancamento_categoria_mesmo_usuario_e_tipo FOREIGN KEY (categoria_id, usuario_id, tipo)
        REFERENCES categoria (id, usuario_id, tipo) ON DELETE NO ACTION
);

-- Lista por usuario e intervalo de data_caixa, e também o saldo por conta (que lê todos os
-- lançamentos do usuário). Medido com 400 mil linhas: lista de um mês em 4 ms, saldo de um
-- usuário com 2 mil lançamentos em 0,7 ms. Não há índice por conta_id nem por categoria_id:
-- um (usuario_id, conta_id) ganhou só 0,3 ms no saldo, e conta e categoria não são apagadas
-- hoje. Quem trouxer essa query (CRUD de categoria, relatório) cria o índice com o EXPLAIN dela.
CREATE INDEX lancamento_usuario_data_caixa ON lancamento (usuario_id, data_caixa);

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre tabelas criadas pelo dono.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga todas as categorias e todos os lançamentos.
-- DROP TABLE lancamento;
-- DROP TABLE categoria;
-- ALTER TABLE conta DROP CONSTRAINT conta_id_usuario_unico;
-- e apague a linha de flyway_schema_history da versão 6.
