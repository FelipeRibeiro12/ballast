-- ADR-054 (emenda ao ADR-026). Ocorrência cancelada e depois apagada some de vez: a chave
-- (template, data, parcela) fica guardada aqui e a materialização pula as datas desta tabela.
-- parcela_ordem 0 significa "sem divisão": a coluna é chave primária e não aceita nulo, e a
-- ordem real vai de 1 a 10 (V9).
-- A FK para o template é ON DELETE CASCADE: apagar a recorrência leva as marcas junto, sem
-- mexer nos dois caminhos que apagam template (recorrência e conta). Sem GRANT: o ALTER DEFAULT
-- PRIVILEGES da V3 cobre a tabela nova.
CREATE TABLE ocorrencia_apagada (
    template_id uuid NOT NULL,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    ocorrencia_data date NOT NULL,
    parcela_ordem smallint NOT NULL DEFAULT 0,
    apagada_em timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (template_id, ocorrencia_data, parcela_ordem),
    CONSTRAINT ocorrencia_apagada_ordem_faixa CHECK (parcela_ordem BETWEEN 0 AND 10),
    CONSTRAINT ocorrencia_apagada_template_do_usuario FOREIGN KEY (template_id, usuario_id)
        REFERENCES template_lancamento (id, usuario_id) ON DELETE CASCADE
);

-- Para desfazer (o Flyway Community não tem down; rode à mão). Perde as marcas, e as
-- ocorrências apagadas voltam como previstas na próxima rodada do job.
-- DROP TABLE ocorrencia_apagada;
