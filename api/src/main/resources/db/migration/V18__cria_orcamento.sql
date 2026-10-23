-- ADR-043. Teto mensal por categoria de despesa, com vigência: a linha vale do mês desde até a
-- próxima linha da mesma categoria. teto nulo é "sem orçamento a partir daqui".
-- tipo_categoria é constante e leva o tipo para a FK composta: o banco recusa categoria de receita
-- e categoria de outro usuário sem gatilho, como na V12. A aplicação não escreve nela.
-- ON DELETE da categoria é CASCADE: o teto não tem sentido sem ela, e não segura a exclusão.
CREATE TABLE orcamento (
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    categoria_id uuid NOT NULL,
    desde date NOT NULL,
    teto numeric(18, 2),
    tipo_categoria text GENERATED ALWAYS AS ('despesa') STORED NOT NULL,
    criado_em timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (usuario_id, categoria_id, desde),
    CONSTRAINT orcamento_categoria_despesa_do_usuario
        FOREIGN KEY (categoria_id, usuario_id, tipo_categoria)
        REFERENCES categoria (id, usuario_id, tipo) ON DELETE CASCADE,
    CONSTRAINT orcamento_desde_dia_1 CHECK (EXTRACT(DAY FROM desde) = 1),
    CONSTRAINT orcamento_desde_faixa CHECK (desde BETWEEN DATE '1900-01-01' AND DATE '2199-12-01'),
    -- O teto do inteiro seguro do JSON, em reais, como o saldo inicial da conta.
    CONSTRAINT orcamento_teto_faixa CHECK (teto > 0 AND teto <= 90071992547409.91)
);

-- A PK serve a busca da linha vigente (usuario_id, categoria_id, desde <= mês). A FK composta da
-- categoria não tem índice próprio: o CASCADE só roda quando categoria for apagada, e a PK começa
-- por usuario_id, que já recorta a busca.
-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre a tabela nova.

-- Para desfazer (rode à mão):
-- DROP TABLE orcamento;
-- e apague a linha de flyway_schema_history da versão 18.
