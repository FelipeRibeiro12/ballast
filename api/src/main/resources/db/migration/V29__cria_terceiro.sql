-- ADR-062. Terceiro: quem usa o cartão do dono e reembolsa depois. O vínculo com o lançamento fica
-- numa tabela à parte, assim lancamento não ganha coluna e a leitura de quem não é terceiro não muda.
-- Despesa ligada ao terceiro é compra dele; receita ligada é reembolso. O papel vem do tipo do
-- lançamento, não de uma coluna.
ALTER TABLE lancamento ADD CONSTRAINT lancamento_id_usuario_unico UNIQUE (id, usuario_id);

CREATE TABLE terceiro (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    nome text NOT NULL,
    arquivado boolean NOT NULL DEFAULT false,
    criado_em timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT terceiro_nome_valido CHECK (nome = btrim(nome) AND char_length(nome) BETWEEN 1 AND 100),
    CONSTRAINT terceiro_id_usuario_unico UNIQUE (id, usuario_id)
);

CREATE UNIQUE INDEX terceiro_usuario_nome_unico ON terceiro (usuario_id, lower(nome));

CREATE TABLE terceiro_lancamento (
    lancamento_id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL,
    terceiro_id uuid NOT NULL,
    -- Apagar o lançamento solta o vínculo. Apagar o terceiro com vínculo é recusado (NO ACTION).
    CONSTRAINT terceiro_lancamento_do_usuario FOREIGN KEY (lancamento_id, usuario_id)
        REFERENCES lancamento (id, usuario_id) ON DELETE CASCADE,
    CONSTRAINT terceiro_lancamento_terceiro_do_usuario FOREIGN KEY (terceiro_id, usuario_id)
        REFERENCES terceiro (id, usuario_id) ON DELETE NO ACTION
);

CREATE INDEX terceiro_lancamento_por_terceiro ON terceiro_lancamento (usuario_id, terceiro_id);

-- Desfazer:
-- DROP TABLE terceiro_lancamento;
-- DROP TABLE terceiro;
-- ALTER TABLE lancamento DROP CONSTRAINT lancamento_id_usuario_unico;
