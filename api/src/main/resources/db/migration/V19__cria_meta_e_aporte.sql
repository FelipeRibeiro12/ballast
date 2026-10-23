-- ADR-045. Meta financeira com aportes registrados à mão. O aporte mensal é plano, não lançamento:
-- nada se confirma sozinho (ADR-028). Retirada é aporte negativo.
CREATE TABLE meta (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    titulo text NOT NULL,
    valor_alvo numeric(18, 2) NOT NULL,
    data_alvo date,
    aporte_mensal numeric(18, 2),
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT meta_titulo_tamanho CHECK (char_length(titulo) BETWEEN 1 AND 100 AND titulo = btrim(titulo)),
    -- O teto do inteiro seguro do JSON, em reais, como o saldo inicial da conta.
    CONSTRAINT meta_valor_alvo_faixa CHECK (valor_alvo > 0 AND valor_alvo <= 90071992547409.91),
    CONSTRAINT meta_aporte_mensal_faixa
        CHECK (aporte_mensal > 0 AND aporte_mensal <= 90071992547409.91),
    CONSTRAINT meta_data_alvo_faixa CHECK (data_alvo BETWEEN DATE '1900-01-01' AND DATE '2199-12-31'),
    -- alvo das FKs compostas
    CONSTRAINT meta_id_usuario_unico UNIQUE (id, usuario_id)
);

-- A FK composta garante que o aporte é de meta do mesmo usuário. CASCADE: apagar a meta leva o
-- histórico dela, que não tem sentido sozinho.
CREATE TABLE aporte_meta (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    meta_id uuid NOT NULL,
    valor numeric(18, 2) NOT NULL,
    data date NOT NULL,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT aporte_meta_meta_do_usuario
        FOREIGN KEY (meta_id, usuario_id) REFERENCES meta (id, usuario_id) ON DELETE CASCADE,
    CONSTRAINT aporte_meta_valor_faixa
        CHECK (valor <> 0 AND abs(valor) <= 90071992547409.91),
    CONSTRAINT aporte_meta_data_faixa CHECK (data BETWEEN DATE '1900-01-01' AND DATE '2199-12-31')
);

-- A lista de metas soma os aportes de cada uma, e a tela de uma meta lista os dela por data.
CREATE INDEX aporte_meta_usuario_meta_data ON aporte_meta (usuario_id, meta_id, data);
CREATE INDEX meta_usuario ON meta (usuario_id);

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre as tabelas novas.

-- Para desfazer (rode à mão):
-- DROP TABLE aporte_meta;
-- DROP TABLE meta;
-- e apague a linha de flyway_schema_history da versão 19.
