-- ADR-023. Id sem DEFAULT: o UUID v7 é gerado na aplicação, como em usuario.
-- CASCADE porque a conta não existe sem o titular, e a exclusão de usuário apaga tudo
-- (docs/ameacas.md).
-- Tipo e moeda ficam restritos ao que a F2 trata. Cada fatia que trouxer outro tipo
-- (beneficio, cartao_credito, cripto) alarga o CHECK por migration nova.
-- O teto do saldo é o Number.MAX_SAFE_INTEGER em centavos: acima disso o JavaScript perde o centavo.
CREATE TABLE conta (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    nome text NOT NULL,
    tipo text NOT NULL,
    moeda char(3) NOT NULL DEFAULT 'BRL',
    saldo_inicial numeric(18, 2) NOT NULL DEFAULT 0,
    arquivada boolean NOT NULL DEFAULT false,
    criada_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT conta_tipo_valido CHECK (tipo IN ('corrente', 'poupanca', 'dinheiro')),
    CONSTRAINT conta_moeda_brl CHECK (moeda = 'BRL'),
    CONSTRAINT conta_nome_tamanho CHECK (char_length(nome) BETWEEN 1 AND 100),
    CONSTRAINT conta_nome_sem_espaco_nas_pontas CHECK (nome = btrim(nome)),
    CONSTRAINT conta_saldo_inicial_faixa
        CHECK (saldo_inicial BETWEEN -90071992547409.91 AND 90071992547409.91)
);

-- Também atende a listagem por usuario_id (prefixo do índice), então não há índice só dele.
-- Vale contra conta arquivada: o nome continua reservado.
CREATE UNIQUE INDEX conta_usuario_nome_unico ON conta (usuario_id, lower(nome));

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre tabelas criadas pelo dono.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga todas as contas.
-- DROP TABLE conta;
-- e apague a linha de flyway_schema_history da versão 5.
