-- ADR-032. Compra parcelada: N linhas de lancamento ligadas por parcela_de_id. Todas as
-- parcelas, inclusive a primeira, apontam para a primeira (que aponta para si mesma).
-- Nulo nas três colunas é "lançamento que não é parcela".

-- Passo 1: colunas nulas, só no catálogo, sem reescrever a tabela.
ALTER TABLE lancamento ADD COLUMN parcela_de_id uuid;
ALTER TABLE lancamento ADD COLUMN parcela_num smallint;
ALTER TABLE lancamento ADD COLUMN parcela_total smallint;

-- Passo 2: os CHECKs. Cada um entra NOT VALID e é validado em seguida, como na V7. Nulo passa
-- em CHECK, então todos valem só para linha de parcela.
ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_colunas_juntas
    CHECK ((parcela_de_id IS NULL) = (parcela_num IS NULL)
        AND (parcela_de_id IS NULL) = (parcela_total IS NULL)) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_colunas_juntas;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_total_faixa
    CHECK (parcela_total BETWEEN 2 AND 48) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_total_faixa;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_num_faixa
    CHECK (parcela_num BETWEEN 1 AND parcela_total) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_num_faixa;

-- A primeira parcela é a que aponta para si mesma, e só ela tem parcela_num 1. Com isso o
-- alvo da FK de baixo (linha que aponta para si) é sempre a parcela 1.
ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_primeira_aponta_para_si
    CHECK ((parcela_de_id = id) = (parcela_num = 1)) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_primeira_aponta_para_si;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_sem_template
    CHECK (parcela_de_id IS NULL OR (template_id IS NULL AND parcela_ordem IS NULL)) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_sem_template;

ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_so_despesa_realizada
    CHECK (parcela_de_id IS NULL OR (tipo = 'despesa' AND status = 'realizado')) NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_so_despesa_realizada;

-- Passo 3: dono, primeira parcela e total, tudo numa FK só, sem gatilho. A FK repete
-- parcela_de_id nas duas pontas: a linha apontada tem de ter id = parcela_de_id (é a chave) e
-- também parcela_de_id = parcela_de_id da própria linha apontada, ou seja, ela aponta para si e
-- pelo CHECK acima é a parcela 1. O parcela_total igual nas duas fecha o total da compra.
-- O unique de 4 colunas da V7 (id, usuario_id, tipo, conta_id) não serve: a FK precisa de um
-- unique com exatamente estas colunas. Custa um índice a mais, pago em toda escrita de lançamento.
-- NO ACTION (checa no fim do comando) pelo mesmo motivo da V6. Apagar a primeira com outras
-- apontando para ela é recusado. Apagar todas numa instrução só passa, e a primeira apontar
-- para si mesma não atrapalha. A exclusão do usuário limpa tudo no cascade.
ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_id_usuario_origem_total_unico
    UNIQUE (id, usuario_id, parcela_de_id, parcela_total);
ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_mesma_compra
    FOREIGN KEY (parcela_de_id, usuario_id, parcela_de_id, parcela_total)
    REFERENCES lancamento (id, usuario_id, parcela_de_id, parcela_total)
    ON DELETE NO ACTION NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_mesma_compra;

-- Passo 4: parcela só em conta de cartão de crédito. Mesma técnica da V10: coluna gerada que
-- carrega o tipo para a FK composta com conta (conta_id_usuario_tipo_unico). Fica nula fora de
-- parcela, e a FK é MATCH SIMPLE, então não checa essas linhas. A aplicação não escreve nela.
-- STORED reescreve lancamento inteira sob lock exclusivo: com 400 mil linhas levou cerca de 1,1 s
-- em Postgres 17 descartável. Aceito: é uma vez, e o app é de uso pessoal.
ALTER TABLE lancamento ADD COLUMN parcela_tipo_conta text
    GENERATED ALWAYS AS (CASE WHEN parcela_de_id IS NULL THEN NULL ELSE 'cartao_credito' END) STORED;
ALTER TABLE lancamento ADD CONSTRAINT lancamento_parcela_conta_cartao
    FOREIGN KEY (conta_id, usuario_id, parcela_tipo_conta)
    REFERENCES conta (id, usuario_id, tipo) ON DELETE NO ACTION NOT VALID;
ALTER TABLE lancamento VALIDATE CONSTRAINT lancamento_parcela_conta_cartao;

-- Busca das parcelas de uma compra e checagem da FK ao apagar a primeira. Parcial porque a
-- maioria dos lançamentos não é parcela. A tabela é grande, mas o Flyway roda em transação e
-- não aceita CONCURRENTLY; a varredura levou 30 ms com 400 mil linhas.
CREATE INDEX lancamento_parcela_de ON lancamento (parcela_de_id) WHERE parcela_de_id IS NOT NULL;

-- Sem GRANT: o ALTER DEFAULT PRIVILEGES da V3 cobre as colunas novas.

-- Para desfazer (o Flyway Community não tem down; rode à mão).
-- Destrutivo: apaga as parcelas e as colunas novas.
-- DROP INDEX lancamento_parcela_de;
-- DELETE FROM lancamento WHERE parcela_de_id IS NOT NULL;
-- ALTER TABLE lancamento DROP COLUMN parcela_tipo_conta; (leva junto lancamento_parcela_conta_cartao)
-- ALTER TABLE lancamento DROP COLUMN parcela_de_id;
--   (leva junto o unique e a FK lancamento_parcela_*, que citam a coluna, e os CHECKs dela)
-- ALTER TABLE lancamento DROP COLUMN parcela_num;
-- ALTER TABLE lancamento DROP COLUMN parcela_total;
-- e apague a linha de flyway_schema_history da versão 11.
