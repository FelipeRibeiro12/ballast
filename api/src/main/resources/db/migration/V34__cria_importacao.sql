-- ADR-069 (F22). Importação de extrato em duas etapas: importacao e importacao_linha guardam o que
-- o arquivo trouxe e a decisão por linha; o lançamento só nasce na confirmação. O arquivo em si
-- não é guardado. Todas as tabelas têm usuario_id e FK composta com o dono do recurso, como a V6.

CREATE TABLE importacao (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    conta_id uuid NOT NULL,
    -- SHA-256 do conteúdo, em hexa minúsculo. O mesmo arquivo não entra duas vezes por usuário.
    hash_arquivo text NOT NULL,
    status text NOT NULL DEFAULT 'pendente',
    total_linhas integer NOT NULL,
    total_invalidas integer NOT NULL,
    criado_em timestamptz NOT NULL DEFAULT now(),
    confirmada_em timestamptz,
    CONSTRAINT importacao_hash_formato CHECK (hash_arquivo ~ '^[0-9a-f]{64}$'),
    CONSTRAINT importacao_status_valido CHECK (status IN ('pendente', 'confirmada')),
    -- 2000 é o teto de transações por arquivo.
    CONSTRAINT importacao_totais_faixa
        CHECK (total_linhas BETWEEN 1 AND 2000 AND total_invalidas BETWEEN 0 AND total_linhas),
    CONSTRAINT importacao_confirmada_com_data CHECK ((status = 'confirmada') = (confirmada_em IS NOT NULL)),
    CONSTRAINT importacao_id_usuario_unico UNIQUE (id, usuario_id),
    CONSTRAINT importacao_usuario_hash_unico UNIQUE (usuario_id, hash_arquivo),
    -- NO ACTION como o lancamento: apagar a conta apaga as importações dela antes (ADR-036).
    CONSTRAINT importacao_conta_do_usuario FOREIGN KEY (conta_id, usuario_id)
        REFERENCES conta (id, usuario_id) ON DELETE NO ACTION
);

CREATE INDEX importacao_usuario_criado_em ON importacao (usuario_id, criado_em DESC);
CREATE INDEX importacao_usuario_conta ON importacao (usuario_id, conta_id);

CREATE TABLE importacao_linha (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    importacao_id uuid NOT NULL,
    -- Posição da transação no arquivo, a partir de 1.
    ordem integer NOT NULL,
    -- Os campos lidos do arquivo, como texto. Pode ter FITID: nunca vai para log.
    bruta jsonb NOT NULL,
    hash_externo text,
    -- Data, tipo e valor ficam nulos só na linha inválida (invalida_motivo preenchido).
    data date,
    tipo text,
    valor numeric(18, 2),
    titulo text NOT NULL,
    descricao text,
    invalida_motivo text,
    proposta text NOT NULL,
    proposta_motivo text,
    decisao text,
    -- Lançamento existente com que a linha concilia (da proposta ou escolhido pelo usuário).
    lancamento_id uuid,
    categoria_id uuid,
    resultado text,
    CONSTRAINT importacao_linha_ordem_faixa CHECK (ordem BETWEEN 1 AND 2000),
    CONSTRAINT importacao_linha_ordem_unica UNIQUE (importacao_id, ordem),
    CONSTRAINT importacao_linha_hash_formato CHECK (hash_externo ~ '^[0-9a-f]{64}$'),
    CONSTRAINT importacao_linha_tipo_valido CHECK (tipo IN ('receita', 'despesa')),
    CONSTRAINT importacao_linha_valor_faixa CHECK (valor > 0 AND valor <= 90071992547409.91),
    CONSTRAINT importacao_linha_data_faixa
        CHECK (data BETWEEN DATE '1900-01-01' AND DATE '2199-12-31'),
    CONSTRAINT importacao_linha_titulo_tamanho
        CHECK (char_length(titulo) BETWEEN 1 AND 100 AND titulo = btrim(titulo)),
    CONSTRAINT importacao_linha_descricao_tamanho CHECK (char_length(descricao) <= 500),
    CONSTRAINT importacao_linha_proposta_valida CHECK (proposta IN ('importar', 'ignorar', 'conciliar')),
    CONSTRAINT importacao_linha_proposta_motivo_valido
        CHECK (proposta_motivo IN ('ja_importada', 'invalida', 'duplicada_no_arquivo')),
    CONSTRAINT importacao_linha_decisao_valida CHECK (decisao IN ('importar', 'ignorar', 'conciliar')),
    CONSTRAINT importacao_linha_resultado_valido
        CHECK (resultado IN ('criada', 'conciliada', 'ignorada', 'duplicada')),
    CONSTRAINT importacao_linha_dados_conforme_validade CHECK (
        (invalida_motivo IS NULL) = (data IS NOT NULL AND tipo IS NOT NULL AND valor IS NOT NULL)),
    -- Linha inválida nunca vira lançamento: só pode ser ignorada.
    CONSTRAINT importacao_linha_invalida_so_ignora CHECK (
        invalida_motivo IS NULL OR (proposta = 'ignorar' AND (decisao IS NULL OR decisao = 'ignorar'))),
    CONSTRAINT importacao_linha_id_usuario_unico UNIQUE (id, usuario_id),
    CONSTRAINT importacao_linha_da_importacao_do_usuario FOREIGN KEY (importacao_id, usuario_id)
        REFERENCES importacao (id, usuario_id) ON DELETE CASCADE,
    -- Apagar o lançamento solta o vínculo; a linha continua. A lista de colunas do SET NULL
    -- (Postgres 15+) deixa usuario_id em paz, que é NOT NULL.
    CONSTRAINT importacao_linha_lancamento_do_usuario FOREIGN KEY (lancamento_id, usuario_id)
        REFERENCES lancamento (id, usuario_id) ON DELETE SET NULL (lancamento_id),
    CONSTRAINT importacao_linha_categoria_do_usuario_e_tipo FOREIGN KEY (categoria_id, usuario_id, tipo)
        REFERENCES categoria (id, usuario_id, tipo) ON DELETE SET NULL (categoria_id)
);

CREATE INDEX importacao_linha_por_lancamento ON importacao_linha (usuario_id, lancamento_id)
    WHERE lancamento_id IS NOT NULL;
CREATE INDEX importacao_linha_por_categoria ON importacao_linha (usuario_id, categoria_id)
    WHERE categoria_id IS NOT NULL;

-- Colunas nulas e sem DEFAULT: o Postgres só mexe no catálogo, sem reescrever a tabela. O que
-- segura lancamento por um instante é o CREATE INDEX e o ADD CONSTRAINT abaixo, que leem a tabela.
ALTER TABLE lancamento ADD COLUMN hash_externo text;
ALTER TABLE lancamento ADD COLUMN importacao_linha_id uuid;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_hash_externo_formato
    CHECK (hash_externo ~ '^[0-9a-f]{64}$');
ALTER TABLE lancamento ADD CONSTRAINT lancamento_importacao_linha_do_usuario
    FOREIGN KEY (importacao_linha_id, usuario_id)
    REFERENCES importacao_linha (id, usuario_id) ON DELETE SET NULL (importacao_linha_id);

-- É o que impede o mesmo FITID de virar dois lançamentos, mesmo com duas confirmações em corrida.
CREATE UNIQUE INDEX lancamento_usuario_hash_externo_unico ON lancamento (usuario_id, hash_externo)
    WHERE hash_externo IS NOT NULL;
-- Alvo do SET NULL ao apagar a linha.
CREATE INDEX lancamento_por_importacao_linha ON lancamento (importacao_linha_id)
    WHERE importacao_linha_id IS NOT NULL;
-- Candidatos à conciliação: lançamentos da conta numa janela de data_competencia.
CREATE INDEX lancamento_usuario_conta_competencia ON lancamento (usuario_id, conta_id, data_competencia);

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre tabelas criadas pelo dono.

-- Desfazer:
-- DROP INDEX lancamento_usuario_conta_competencia;
-- DROP INDEX lancamento_por_importacao_linha;
-- DROP INDEX lancamento_usuario_hash_externo_unico;
-- ALTER TABLE lancamento DROP CONSTRAINT lancamento_importacao_linha_do_usuario;
-- ALTER TABLE lancamento DROP CONSTRAINT lancamento_hash_externo_formato;
-- ALTER TABLE lancamento DROP COLUMN importacao_linha_id;
-- ALTER TABLE lancamento DROP COLUMN hash_externo;
-- DROP TABLE importacao_linha;
-- DROP TABLE importacao;
-- e apague a linha de flyway_schema_history da versão 34.
