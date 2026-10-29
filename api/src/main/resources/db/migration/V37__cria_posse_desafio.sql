-- ADR-072. Desafio de prova de posse da carteira observada: uma linha por carteira, então pedir
-- outro desafio substitui o anterior. A verificação consome a linha por DELETE ... RETURNING antes
-- de olhar a assinatura. carteira_observada.verificada_em (V36) guarda o resultado.

-- A FK composta exige que o desafio seja do mesmo usuário da carteira. CASCADE: apagar a carteira
-- leva o desafio. A hora de emissão não tem coluna: é expira_em menos o TTL fixo da aplicação.
CREATE TABLE posse_desafio (
    carteira_id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL,
    nonce text NOT NULL,
    mensagem text NOT NULL,
    expira_em timestamptz NOT NULL,
    CONSTRAINT posse_desafio_nonce_formato CHECK (nonce ~ '^[0-9a-f]{32}$'),
    CONSTRAINT posse_desafio_mensagem_tamanho CHECK (char_length(mensagem) BETWEEN 1 AND 1000),
    CONSTRAINT posse_desafio_carteira_do_usuario FOREIGN KEY (carteira_id, usuario_id)
        REFERENCES carteira_observada (id, usuario_id) ON DELETE CASCADE
);

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre a tabela nova.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- DROP TABLE posse_desafio;
-- e apague a linha de flyway_schema_history da versão 37.
