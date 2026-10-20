-- ADR-027. Divisão de salário: parcelas do template e a ordem da parcela no lançamento.
-- Id sem DEFAULT (UUID v7 vem da aplicação). FKs de dado em NO ACTION pelo mesmo motivo da V6 e
-- da V8: apagar o usuário limpa as tabelas no mesmo cascade, em qualquer ordem. Fica NO ACTION
-- também para o template, e não CASCADE: template não se apaga hoje, e se um dia se apagar, perder
-- as parcelas em silêncio seria pior que a recusa.

-- O modo único por template (só percentual ou só valor fixo) e a soma ficam com a aplicação
-- (ADR-027). Garantir o modo no banco pediria uma coluna modo no template e outra na parcela,
-- amarradas por FK composta e por CHECK, só para um erro que o serviço já recusa.
CREATE TABLE template_divisao (
    id uuid PRIMARY KEY,
    usuario_id uuid NOT NULL REFERENCES usuario (id) ON DELETE CASCADE,
    template_id uuid NOT NULL,
    dia_do_mes smallint NOT NULL,
    percentual numeric(5, 2),
    valor_fixo numeric(18, 2),
    ordem smallint NOT NULL,
    -- Percentual 0 e 100 ficam de fora: parcela vazia não existe, e 100 seria a divisão em uma
    -- parcela só, que o ADR-027 recusa (mínimo de 2).
    CONSTRAINT divisao_percentual_faixa CHECK (percentual > 0 AND percentual < 100),
    CONSTRAINT divisao_valor_fixo_faixa CHECK (valor_fixo > 0 AND valor_fixo <= 90071992547409.91),
    CONSTRAINT divisao_dia_do_mes_faixa CHECK (dia_do_mes BETWEEN 1 AND 31),
    -- Teto de 10 parcelas do ADR-027.
    CONSTRAINT divisao_ordem_faixa CHECK (ordem BETWEEN 1 AND 10),
    CONSTRAINT divisao_percentual_ou_valor CHECK ((percentual IS NULL) <> (valor_fixo IS NULL)),
    -- Os dois únicos também servem a leitura da materialização, que filtra por template_id.
    CONSTRAINT divisao_template_dia_unico UNIQUE (template_id, dia_do_mes),
    CONSTRAINT divisao_template_ordem_unica UNIQUE (template_id, ordem),
    CONSTRAINT divisao_template_mesmo_usuario FOREIGN KEY (template_id, usuario_id)
        REFERENCES template_lancamento (id, usuario_id) ON DELETE NO ACTION
);

-- Coluna nula e sem DEFAULT: só catálogo, sem reescrever a tabela. CHECKs em NOT VALID e depois
-- VALIDATE, como na V8.
ALTER TABLE lancamento ADD COLUMN parcela_ordem smallint;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_ordem_faixa
    CHECK (parcela_ordem BETWEEN 1 AND 10) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_ordem_faixa;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_so_com_template
    CHECK (parcela_ordem IS NULL OR template_id IS NOT NULL) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_so_com_template;

-- Nova chave de idempotência (ADR-027). NULLS NOT DISTINCT mantém a regra da V8 para template sem
-- divisão: duas ocorrências na mesma data, ambas sem parcela, continuam colidindo. Sem isso o nulo
-- deixaria passar duplicata. O WHERE template_id IS NOT NULL é obrigatório: com NULLS NOT DISTINCT
-- em todas as colunas, dois lançamentos avulsos (tudo nulo) colidiriam entre si. Por ser índice
-- parcial, não pode ser constraint. A nova entra antes de a velha cair, para nunca ficar sem chave.
-- Criar o índice segura o lock que bloqueia escrita em lancamento até terminar; é uma varredura
-- da tabela, segundos mesmo com milhões de linhas, e CONCURRENTLY não roda dentro da transação do
-- Flyway. Aceito para o porte do app.
-- O ON CONFLICT do backend tem de repetir as três colunas e o predicado:
-- ON CONFLICT (template_id, ocorrencia_data, parcela_ordem) WHERE template_id IS NOT NULL DO NOTHING
CREATE UNIQUE INDEX lancamento_template_ocorrencia_parcela_unica
    ON lancamento (template_id, ocorrencia_data, parcela_ordem) NULLS NOT DISTINCT
    WHERE template_id IS NOT NULL;

-- O prefixo template_id do índice novo cobre a FK e a desativação, como o velho cobria.
ALTER TABLE lancamento DROP CONSTRAINT lancamento_template_ocorrencia_unica;

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre a tabela nova.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga as parcelas e os lançamentos de parcela ficam sem a ordem. Antes, apague ou
-- junte as ocorrências com parcela_ordem, senão a chave antiga (template_id, ocorrencia_data)
-- recusa as que dividem a data:
-- DELETE FROM lancamento WHERE parcela_ordem IS NOT NULL;
-- ALTER TABLE lancamento ADD CONSTRAINT lancamento_template_ocorrencia_unica
--     UNIQUE (template_id, ocorrencia_data);
-- DROP INDEX lancamento_template_ocorrencia_parcela_unica;
-- ALTER TABLE lancamento DROP COLUMN parcela_ordem;
--   (a coluna leva junto os dois CHECKs)
-- DROP TABLE template_divisao;
-- e apague a linha de flyway_schema_history da versão 9.
