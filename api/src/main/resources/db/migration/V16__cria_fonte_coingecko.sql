-- ADR-040. Cotação automática: o job grava em cotacao com a fonte coingecko, ao lado da manual.
-- NOT VALID e depois VALIDATE, como na V15: a troca do CHECK não segura a tabela durante a varredura.
ALTER TABLE cotacao DROP CONSTRAINT cotacao_fonte_valida;
ALTER TABLE cotacao ADD CONSTRAINT cotacao_fonte_valida
    CHECK (fonte IN ('manual', 'coingecko')) NOT VALID;
ALTER TABLE cotacao VALIDATE CONSTRAINT cotacao_fonte_valida;

-- Para desfazer (rode à mão):
-- DELETE FROM cotacao WHERE fonte = 'coingecko';
-- ALTER TABLE cotacao DROP CONSTRAINT cotacao_fonte_valida;
-- ALTER TABLE cotacao ADD CONSTRAINT cotacao_fonte_valida CHECK (fonte IN ('manual'));
-- e apague a linha de flyway_schema_history da versão 16.
