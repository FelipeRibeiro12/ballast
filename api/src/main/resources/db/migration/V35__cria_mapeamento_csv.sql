-- ADR-070 (F23). Mapeamento de colunas de um CSV de banco, salvo por usuário para o próximo envio.
-- Guarda só como ler o arquivo (separador, formato de data, colunas). O arquivo e o conteúdo dele
-- ficam nas tabelas de importação, que a F22 criou.

CREATE TABLE mapeamento_csv (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    nome text NOT NULL,
    -- Os mesmos nomes do contrato da API: o ';' e o TAB não viajam bem numa query.
    separador text NOT NULL,
    formato_data text NOT NULL,
    decimal text NOT NULL,
    -- positivo_despesa serve ao extrato de cartão, onde a compra vem positiva.
    sinal text NOT NULL,
    tem_cabecalho boolean NOT NULL,
    -- Índices de coluna a partir de 0. O arquivo aceita no máximo 50 colunas.
    coluna_data smallint NOT NULL,
    coluna_valor smallint NOT NULL,
    coluna_descricao smallint NOT NULL,
    coluna_id smallint,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT mapeamento_csv_nome_tamanho
        CHECK (char_length(nome) BETWEEN 1 AND 60 AND nome = btrim(nome)),
    CONSTRAINT mapeamento_csv_separador_valido
        CHECK (separador IN ('ponto_e_virgula', 'virgula', 'tab', 'barra_vertical')),
    CONSTRAINT mapeamento_csv_formato_data_valido
        CHECK (formato_data IN ('dd/MM/yyyy', 'yyyy-MM-dd', 'MM/dd/yyyy', 'dd-MM-yyyy')),
    CONSTRAINT mapeamento_csv_decimal_valido CHECK (decimal IN ('virgula', 'ponto')),
    CONSTRAINT mapeamento_csv_sinal_valido CHECK (sinal IN ('negativo_despesa', 'positivo_despesa')),
    CONSTRAINT mapeamento_csv_colunas_faixa CHECK (
        coluna_data BETWEEN 0 AND 49 AND coluna_valor BETWEEN 0 AND 49
        AND coluna_descricao BETWEEN 0 AND 49 AND (coluna_id IS NULL OR coluna_id BETWEEN 0 AND 49)),
    -- Uma célula não pode valer por dois campos.
    CONSTRAINT mapeamento_csv_colunas_distintas CHECK (
        coluna_data <> coluna_valor AND coluna_data <> coluna_descricao
        AND coluna_valor <> coluna_descricao
        AND (coluna_id IS NULL OR (coluna_id <> coluna_data AND coluna_id <> coluna_valor
                                   AND coluna_id <> coluna_descricao))),
    CONSTRAINT mapeamento_csv_id_usuario_unico UNIQUE (id, usuario_id),
    CONSTRAINT mapeamento_csv_usuario_nome_unico UNIQUE (usuario_id, nome)
);

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre tabelas criadas pelo dono.

-- Desfazer:
-- DROP TABLE mapeamento_csv;
-- e apague a linha de flyway_schema_history da versão 35.
