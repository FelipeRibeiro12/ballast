-- ADR-041. Histórico de cotação para trás: o job busca uma vez por ativo os dias antes da primeira
-- cotação e marca aqui quando buscou. Nulo é pendente, e os ativos que já existem entram na fila.
-- Coluna nula e sem default não reescreve a tabela.
ALTER TABLE ativo_cripto ADD COLUMN historico_em timestamptz;

-- Para desfazer (rode à mão):
-- ALTER TABLE ativo_cripto DROP COLUMN historico_em;
-- e apague a linha de flyway_schema_history da versão 17.
-- As cotações que o histórico gravou ficam: são linhas coingecko como as do job.
