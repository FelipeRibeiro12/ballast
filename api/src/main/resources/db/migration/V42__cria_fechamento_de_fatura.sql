-- ADR-079. Fechamento manual da fatura: o banco fecha antes do dia configurado e o dono avisa o
-- app. A fatura é identificada pelo vencimento (data_caixa), como em GET /contas/{id}/faturas.
CREATE TABLE fatura_fechamento (
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    conta_id uuid NOT NULL,
    vencimento date NOT NULL,
    fechada_em date NOT NULL,
    criado_em timestamptz NOT NULL DEFAULT now(),
    -- Constante, só para a FK abaixo exigir que a conta seja de cartão e do mesmo usuário.
    tipo_conta text GENERATED ALWAYS AS ('cartao_credito') STORED,
    PRIMARY KEY (usuario_id, conta_id, vencimento),
    CONSTRAINT fatura_fechamento_conta_do_usuario FOREIGN KEY (conta_id, usuario_id, tipo_conta)
        REFERENCES conta (id, usuario_id, tipo) ON DELETE CASCADE
);

-- Desfazer:
-- DROP TABLE fatura_fechamento;
