-- ADR-068. Visão de saldo: um cartão do carrossel do Início, com título e os tipos de conta que ele
-- soma. A visão padrão "Total sem cartão" nasce na primeira leitura e é uma linha como as outras.
-- O limite de 10 por usuário vive na aplicação (UserLimits), com a trava consultiva.
CREATE TABLE visao_saldo (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    titulo text NOT NULL,
    tipos text[] NOT NULL,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT visao_saldo_titulo_valido
        CHECK (titulo = btrim(titulo) AND char_length(titulo) BETWEEN 1 AND 100),
    CONSTRAINT visao_saldo_tipos_faixa CHECK (cardinality(tipos) BETWEEN 1 AND 8),
    CONSTRAINT visao_saldo_tipos_validos CHECK (
        tipos <@ ARRAY['corrente', 'poupanca', 'dinheiro', 'cartao_credito', 'beneficio', 'cripto',
                       'investimento', 'cofrinho']::text[])
);

CREATE INDEX visao_saldo_por_usuario ON visao_saldo (usuario_id, criado_em, id);

-- Desfazer:
-- DROP TABLE visao_saldo;
